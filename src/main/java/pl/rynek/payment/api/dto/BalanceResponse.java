package pl.rynek.payment.api.dto;

import pl.rynek.payment.payouts.PayoutService.Balance;

/** Kształt {@code Balance} z rynek-frontend: dostępne do wypłaty i oczekujące (zamówienia jeszcze niezrealizowane). */
public record BalanceResponse(MoneyDto available, MoneyDto pending) {

    public static BalanceResponse of(Balance balance) {
        return new BalanceResponse(MoneyDto.pln(balance.available()), MoneyDto.pln(balance.pending()));
    }
}
