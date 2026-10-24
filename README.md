# rynek-payment-service

`payment-service` — płatności i wypłaty

> Status: **szkielet**. Wszystkie endpointy z tabeli istnieją w kontrolerach (`src/main/java/.../api/`) i zwracają `501 Not Implemented` (RFC 7807). Ciała żądań to puste rekordy w `api/dto/`.

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

## Zdarzenia Kafka

Wiersze z §17, w których występuje `payment` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `payment.succeeded`, `payment.failed`, `payment.refunded` | payment | order, booking, notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL
- **Zależności:** dostawca płatności za interfejsem PaymentProvider (Przelewy24 / PayU / Stripe — do decyzji)
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 5. `cart-service`, `order-service`, `payment-service`, `shipping-service` — sprzedaż produktów.
