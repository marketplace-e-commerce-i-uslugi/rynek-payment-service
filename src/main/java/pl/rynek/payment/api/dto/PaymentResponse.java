package pl.rynek.payment.api.dto;

import java.util.List;
import java.util.UUID;

import pl.rynek.payment.payments.Payment;
import pl.rynek.payment.payments.PaymentMethod;
import pl.rynek.payment.payments.PaymentStatus;
import pl.rynek.payment.provider.PaymentProvider;

/**
 * Kształt {@code Payment} z rynek-frontend plus {@code method}, {@code orderIds} i {@code reason} (powód niepowodzenia).
 * {@code redirectUrl} jest tylko przy karcie i przelewie w toku.
 */
public record PaymentResponse(UUID id, PaymentStatus status, MoneyDto amount, String redirectUrl, PaymentMethod method,
                              List<UUID> orderIds, String reason) {

    public static PaymentResponse of(Payment p, PaymentProvider provider) {
        String redirect = p.getStatus() == PaymentStatus.PENDING ? provider.redirectUrl(p.getId()) : null;
        return new PaymentResponse(p.getId(), p.getStatus(), MoneyDto.pln(p.getAmount()), redirect, p.getMethod(), p.getReferences(),
                p.getStatusReason());
    }
}
