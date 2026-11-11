package pl.rynek.payment.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.Size;

/**
 * Zwrot środków. {@code orderId}: zamówienie, za które wracają pieniądze (można pominąć, gdy płatność obejmuje jedno
 * zamówienie wołającego). {@code amount}: kwota w groszach; pominięta = cała pozostała kwota zamówienia.
 */
public record RefundRequest(UUID orderId, MoneyDto amount,
                            @Size(max = 200, message = "Powód zwrotu może mieć najwyżej 200 znaków.") String reason) {
}
