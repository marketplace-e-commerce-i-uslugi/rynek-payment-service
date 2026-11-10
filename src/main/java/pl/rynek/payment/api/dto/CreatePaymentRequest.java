package pl.rynek.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pl.rynek.payment.payments.PaymentMethod;
import pl.rynek.payment.payments.ReferenceType;

/** {@code referenceId}: id zamówień rozdzielone przecinkami (zakup z koszyka od kilku sprzedawców to kilka zamówień). */
public record CreatePaymentRequest(
        @NotNull(message = "Podaj, za co płacisz.") ReferenceType referenceType,
        @NotBlank(message = "Podaj zamówienie do opłacenia.") @Size(max = 800, message = "Za dużo zamówień w jednej płatności.") String referenceId,
        @NotNull(message = "Wybierz metodę płatności.") PaymentMethod method) {
}
