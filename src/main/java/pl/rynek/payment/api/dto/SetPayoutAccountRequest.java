package pl.rynek.payment.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Kształt {@code PayoutAccount} z rynek-frontend. IBAN może mieć spacje; sam numer polskiego rachunku dostaje prefiks PL. */
public record SetPayoutAccountRequest(@NotBlank(message = "Podaj właściciela rachunku.") String holder,
                                      @NotBlank(message = "Podaj numer rachunku.") String iban) {
}
