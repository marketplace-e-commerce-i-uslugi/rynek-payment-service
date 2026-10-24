package pl.rynek.payment.api.dto;

/**
 * Ciało żądania: Rozpoczęcie płatności za zamówienie lub zaliczkę rezerwacji (referenceType: ORDER / BOOKING, Idempotency-Key). Pola do uzupełnienia razem z kontraktem w rynek-contracts.
 */
public record CreatePaymentRequest() {
}
