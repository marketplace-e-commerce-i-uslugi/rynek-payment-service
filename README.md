# rynek-payment-service

`payment-service` — płatności i wypłaty

> Status: **płatności za zamówienia działają** z dostawcą `sandbox` (symulator bez prawdziwych pieniędzy): metody, rozpoczęcie płatności, BLIK, status, webhook z podpisem i oznaczanie zamówień jako opłaconych w order-service. Zwroty, saldo, wypłaty i rachunek do wypłat oraz zaliczki za rezerwacje zwracają `501 Not Implemented` / 422. Prawdziwy dostawca (Przelewy24 / PayU / Stripe) to decyzja biznesowa.

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
| PUT | `/api/v1/seller/payout-account` | Rachunek do wypłat | seller |
| GET, POST | `/api/v1/payments/sandbox/{paymentId}` | Strona symulatora dostawcy dla karty i przelewu (tylko `provider=sandbox`) | pub |

### Zasady

- `POST /payments {referenceType, referenceId, method}` z `Idempotency-Key`. `referenceId` to id zamówień rozdzielone przecinkami (zakup z koszyka od kilku sprzedawców = jedna płatność). Kwota to suma zamówień; każde musi należeć do płacącego i czekać na płatność. Nowa płatność zastępuje niedokończone płatności za te same zamówienia (status `FAILED`, „Zastąpiona nową płatnością.”).
- BLIK: `WAITING_FOR_BLIK` → `POST /{id}/blik {code}`. Odrzucony kod daje 422 i płatność czeka na kolejny; po 3 odrzuceniach jest `FAILED`. W symulatorze bank odrzuca tylko kod `000000`.
- Karta i przelew: `PENDING` z `redirectUrl` do strony dostawcy. Wynik przychodzi webhookiem `POST /payments/webhooks/{provider}` z nagłówkiem `X-Signature` (HMAC-SHA256 treści, sekret `PAYMENT_WEBHOOK_SECRET`) i treścią `{paymentId, result: SUCCEEDED|FAILED}`. Ponowiony webhook dla rozliczonej płatności niczego nie zmienia. Strona symulatora rozlicza płatność tak samo i przekierowuje (303) na `/zamowienie/potwierdzenie` frontendu.
- Przed zaksięgowaniem płatność sprawdza, czy zamówienia nadal czekają (mogły wygasnąć po 30 min). Jeśli nie, płatność jest `FAILED` i pieniądze nie są pobierane. Po sukcesie zamówienia są oznaczane jako opłacone (`mark-paid` z `paymentId`); gdy order-service nie odpowiada, próba jest ponawiana co minutę (`orders_confirmed`).
- `Payment` ma więcej pól niż kształt z frontendu: `method`, `orderIds`, `reason` (powód niepowodzenia).

## Zdarzenia Kafka

Wiersze z §17, w których występuje `payment` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `payment.succeeded`, `payment.failed`, `payment.refunded` | payment | order, booking, notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL (baza `payments`)
- **Zależności:** dostawca płatności za interfejsem `PaymentProvider` (na razie `SandboxProvider`; Przelewy24 / PayU / Stripe do decyzji), order-service (`GET /internal/orders/{id}`, `POST /internal/orders/{id}/mark-paid`)
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 5. `cart-service`, `order-service`, `payment-service`, `shipping-service` — sprzedaż produktów.
