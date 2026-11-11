package pl.rynek.payment.api.dto;

import pl.rynek.payment.payouts.PayoutAccount;

/** Kształt {@code PayoutAccount} z rynek-frontend; IBAN w grupach po 4 znaki. */
public record PayoutAccountResponse(String holder, String iban) {

    public static PayoutAccountResponse of(PayoutAccount account) {
        return new PayoutAccountResponse(account.getHolder(), account.getFormattedIban());
    }
}
