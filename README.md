# rynek-payment-service

`payment-service` — płatności i wypłaty

> Status: **płatności, zaliczki, zwroty, saldo i wypłaty działają** z dostawcą `sandbox` (symulator bez prawdziwych pieniędzy): metody, rozpoczęcie płatności za zamówienia i zaliczki za rezerwacje, BLIK, status, webhook z podpisem, oznaczanie zamówień i rezerwacji jako opłaconych, zwroty (ręczne i po anulowaniu), saldo sprzedawcy, rachunek do wypłat i automatyczne wypłaty. Prawdziwy dostawca (Przelewy24 / PayU / Stripe) to decyzja biznesowa.

## Uruchomienie

```bash
./mvnw spring-boot:run   # port 8092, zmienna PORT
./mvnw verify            # testy
```

- Swagger UI: http://localhost:8092/swagger-ui.html
- Zdrowie: http://localhost:8092/actuator/health

## Zakres i endpointy

_Skopiowane z `rynek-docs/architektura/rynek-endpointy-mikroserwisy.md`. Od teraz to repozytorium jest właścicielem tej specyfikacji._

Ekrany: 8 Płatność, 9 Panel (przychód). Zalecenie: jeden dostawca płatności (np. Przelewy24, PayU albo Stripe — decyzja biznesowa) za interfejsem `PaymentProvider`; BLIK, karta i przelew online. Dane kart nigdy nie przechodzą przez Rynek.

| Metoda | Ścieżka | Opis | Dostęp |
|---|---|---|---|
| GET | `/api/v1/payments/methods` | Dostępne metody płatności | user |
| POST | `/api/v1/payments` | Rozpoczęcie płatności za zamówienie lub zaliczkę rezerwacji (`referenceType`: ORDER / BOOKING, `Idempotency-Key`) | user |
| POST | `/api/v1/payments/{paymentId}/blik` | Potwierdzenie kodem BLIK | user |
| GET | `/api/v1/payments/{paymentId}` | Status płatności | user |
| POST | `/api/v1/payments/webhooks/{provider}` | Webhook dostawcy płatności (weryfikacja podpisu) | pub (podpis) |
| POST | `/api/v1/payments/{paymentId}/refund` | Zwrot środków | seller / admin |
| GET | `/api/v1/seller/payouts` | Historia wypłat | seller |
| GET | `/api/v1/seller/balance` | Saldo i środki oczekujące | seller |
| GET | `/api/v1/seller/payout-account` | Zapisany rachunek do wypłat, 404 gdy go nie ma (poza dokumentem architektury) | seller |
| PUT | `/api/v1/seller/payout-account` | Rachunek do wypłat | seller |
| GET, POST | `/api/v1/payments/sandbox/{paymentId}` | Strona symulatora dostawcy dla karty i przelewu (tylko `provider=sandbox`) | pub |
| POST | `/internal/bookings/{bookingId}/cancelled` | Rezerwacja anulowana albo wygasła: zwrot zaliczki, płatności w toku `FAILED` | int |
| POST | `/internal/orders/{orderId}/cancelled` | Zamówienie anulowane albo wygasłe: zwrot za opłacone, płatności w toku `FAILED` (zamiast zdarzenia `order.cancelled`) | int |

### Zasady

- `POST /payments {referenceType, referenceId, method}` z `Idempotency-Key`. `referenceId` to id zamówień rozdzielone przecinkami (zakup z koszyka od kilku sprzedawców = jedna płatność). Kwota to suma zamówień; każde musi należeć do płacącego i czekać na płatność. Nowa płatność zastępuje niedokończone płatności za te same zamówienia (status `FAILED`, „Zastąpiona nową płatnością.”).
- BLIK: `WAITING_FOR_BLIK` → `POST /{id}/blik {code}`. Odrzucony kod daje 422 i płatność czeka na kolejny; po 3 odrzuceniach jest `FAILED`. W symulatorze bank odrzuca tylko kod `000000`.
- Karta i przelew: `PENDING` z `redirectUrl` do strony dostawcy. Wynik przychodzi webhookiem `POST /payments/webhooks/{provider}` z nagłówkiem `X-Signature` (HMAC-SHA256 treści, sekret `PAYMENT_WEBHOOK_SECRET`) i treścią `{paymentId, result: SUCCEEDED|FAILED}`. Ponowiony webhook dla rozliczonej płatności niczego nie zmienia. Strona symulatora rozlicza płatność tak samo i przekierowuje (303) na `/zamowienie/potwierdzenie` frontendu.
- Przed zaksięgowaniem płatność sprawdza, czy zamówienia nadal czekają (mogły wygasnąć po 30 min). Jeśli nie, płatność jest `FAILED` i pieniądze nie są pobierane. Po sukcesie zamówienia są oznaczane jako opłacone (`mark-paid` z `paymentId`); gdy order-service nie odpowiada, próba jest ponawiana co minutę (`orders_confirmed`).
- `Payment` ma więcej pól niż kształt z frontendu: `method`, `orderIds`, `reason` (powód niepowodzenia), `refunded` (zwrócona część).

### Zaliczki za rezerwacje

- `POST /payments {referenceType: BOOKING, referenceId: <id rezerwacji>, method}`: jedna rezerwacja klienta w statusie „Czeka na zaliczkę” (`AWAITING_DEPOSIT` z booking-service), kwota to zaliczka (0 daje 422). Po zaksięgowaniu booking-service dostaje `mark-paid` (z ponawianiem), a usługodawca przychód (`seller_earnings.reference_type = BOOKING`), dostępny do wypłaty po „Wykonana”. Strona symulatora wraca na `/zamowienie/potwierdzenie?payment=…&booking=…`.
- Anulowanie albo wygaśnięcie rezerwacji (`POST /internal/bookings/{id}/cancelled`) zwraca całą zaliczkę i oznacza płatność w toku jako nieudaną, tak jak przy zamówieniach.

### Zwroty

- Zwrot dotyczy zawsze jednego zamówienia (płatność z koszyka obejmuje zamówienia kilku sprzedawców). Płatność ma `refunded`; po zwróceniu całej kwoty ma status `REFUNDED`.
- `POST /payments/{id}/refund {orderId?, amount?: {amount, currency}, reason?}`, opcjonalnie z `Idempotency-Key`. Może go wysłać sprzedawca zamówienia albo `ADMIN` (rola z JWT). `orderId` można pominąć, gdy płatność obejmuje jedno zamówienie wołającego. Bez `amount` wraca cała pozostała kwota. Za dużo: 422 „Możesz zwrócić najwyżej …”, całość już wróciła: 409.
- Zwrot ręczny jest dla zamówień wysłanych, zrealizowanych albo anulowanych. Dla niewysłanego („Do wysłania”) jest 409: takie zamówienie się anuluje, a pieniądze wracają automatycznie.
- Anulowanie i wygaśnięcie: order-service woła `POST /internal/orders/{id}/cancelled` (ponawia co minutę, gdy payment nie odpowiada). Płatności w toku dostają `FAILED` („Zamówienie zostało anulowane albo wygasło…”), a za opłacone zamówienie wraca cała pozostała kwota. Klucz `cancel:<orderId>` sprawia, że ponowienie nie zwraca pieniędzy drugi raz. Kolizja z rozliczaną właśnie płatnością daje 503 (ponów).
- Zwrot od sprzedawcy albo admina trafia też do order-service (`POST /internal/orders/{id}/refunded`), więc zgłoszony przez kupującego zwrot staje się przyjęty. Gdy order-service nie odpowiada, zgłoszenie jest ponawiane co `rynek.payments.refund-notify-retry` (60 s, flaga `order_notified`). Zwroty po anulowaniu niczego nie zgłaszają.
- Gdy zamówienie anulowano w chwili księgowania płatności (`mark-paid` → 409), pieniądze za nie wracają automatycznie.
- Zwroty jednej płatności blokują jej wiersz (`SELECT … FOR UPDATE`), więc równoległe zwroty nie przekroczą zapłaconej kwoty. W `sandbox` zwrot przechodzi od razu. Prawdziwy dostawca rozlicza zwroty asynchronicznie, więc zwrot dostanie wtedy własny status i webhook.

### Saldo i wypłaty

- Zaksięgowanie płatności zapisuje przychód każdego sprzedawcy z zamówienia (`seller_earnings`). Płatności zaksięgowane przed wprowadzeniem salda dostają przychód automatycznie: co `rynek.payments.earnings-backfill` (60 s) kwotę i sprzedawcę podaje order-service. Przy zwrocie przychód jest uzupełniany od razu.
- `GET /seller/balance {available, pending}`. `pending`: opłacone zamówienia jeszcze niezrealizowane, minus zwroty. `available`: zamówienia „Zrealizowane” minus zwroty i wszystkie wypłaty. Zwrot po wypłacie może dać ujemne `available`, a wtedy kolejna wypłata czeka. Statusy zamówień payment-service czyta z order-service (`GET /internal/orders?ids=`) przy odczycie salda i przed wypłatą, zamiast słuchać `order.completed`. Gdy order-service nie odpowiada, saldo liczy się z ostatnio znanych statusów.
- `PUT /seller/payout-account {holder, iban}`: IBAN z sumą kontrolną mod 97 (sam 26-cyfrowy numer dostaje `PL`), odpowiedź zwraca IBAN w grupach po 4 znaki. `GET /seller/payout-account` (endpoint spoza dokumentu architektury) zwraca zapisany rachunek albo 404, a formularz we frontendzie wypełnia się z niego przy wejściu na stronę.
- Wypłaty są automatyczne, a w specyfikacji nie ma endpointu do ich zlecania. `rynek.payouts.cron` (domyślnie codziennie o 6:00, `PAYOUTS_CRON`) wypłaca całe `available` (co najmniej `rynek.payouts.minimum`, domyślnie 1 zł) na zapisany rachunek. Bez rachunku wypłaty nie ma. Status „W realizacji” zmienia się na „Wypłacono”, gdy przelew dotrze (w `sandbox` po `rynek.payouts.sandbox-transfer-time`, domyślnie 1 dzień). `GET /seller/payouts`: kursor `{items, nextCursor}`, od najnowszej.
- Prowizji Rynku i okresu karencji po realizacji (np. 14 dni na odstąpienie od umowy) jeszcze nie ma, bo to decyzje biznesowe. Przebieg wypłat zakłada jedną instancję serwisu, choć blokada rachunku chroni przed podwójną wypłatą. Przy wielu instancjach trzeba dodać blokadę harmonogramu (np. ShedLock).

## Zdarzenia Kafka

Wiersze z §17, w których występuje `payment` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `payment.succeeded`, `payment.failed`, `payment.refunded` | payment | order, booking, notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL (baza `payments`)
- **Zależności:** dostawca płatności za interfejsem `PaymentProvider` (na razie `SandboxProvider`; Przelewy24 / PayU / Stripe do decyzji), booking-service (`GET /internal/bookings/{id}`, `?ids=`, `mark-paid`; woła `/internal/bookings/{id}/cancelled`), order-service (`GET /internal/orders/{id}`, `GET /internal/orders?ids=`, `POST /internal/orders/{id}/mark-paid`); order-service woła `POST /internal/orders/{id}/cancelled`
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 5. `cart-service`, `order-service`, `payment-service`, `shipping-service` — sprzedaż produktów.
