package pl.rynek.payment.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmBlikRequest(@NotBlank(message = "Podaj kod BLIK.") String code) {
}
