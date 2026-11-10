package pl.rynek.payment.api.dto;

import pl.rynek.payment.payments.PaymentMethod;

/** Kształt {@code PaymentMethod} z rynek-frontend. */
public record PaymentMethodResponse(PaymentMethod id, String name, String description) {

    public static PaymentMethodResponse of(PaymentMethod m) {
        return new PaymentMethodResponse(m, m.displayName(), m.description());
    }
}
