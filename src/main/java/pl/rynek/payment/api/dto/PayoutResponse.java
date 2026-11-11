package pl.rynek.payment.api.dto;

import java.time.Instant;
import java.util.UUID;

import pl.rynek.payment.payouts.Payout;
import pl.rynek.payment.payouts.PayoutStatus;

/** Kształt {@code Payout} z rynek-frontend; {@code status}: „W realizacji” albo „Wypłacono”. */
public record PayoutResponse(UUID id, MoneyDto amount, Instant createdAt, PayoutStatus status) {

    public static PayoutResponse of(Payout p) {
        return new PayoutResponse(p.getId(), MoneyDto.pln(p.getAmount()), p.getCreatedAt(), p.getStatus());
    }
}
