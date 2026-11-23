package pl.rynek.payment.api.dto;

import java.time.Instant;
import java.util.UUID;

import pl.rynek.payment.payments.ReferenceType;
import pl.rynek.payment.payments.SellerEarning;

/**
 * Przychód z jednego zamówienia albo rezerwacji: {@code gross} to kwota zapłacona przez kupującego (pomniejszona o zwroty),
 * {@code commission} prowizja platformy od niezwróconej części, {@code net} to co zostaje sprzedawcy.
 */
public record EarningResponse(UUID referenceId, ReferenceType type, String label, MoneyDto gross, MoneyDto commission, MoneyDto net,
                              boolean released, boolean refunded, Instant createdAt) {

    /** {@code label}: np. „Zamówienie #1078” albo „Rezerwacja „Montaż mebli””; pusty, gdy serwis zamówień nie odpowiedział. {@code refunded}: zwrócono całość. */
    public static EarningResponse of(SellerEarning e, String label) {
        return new EarningResponse(e.getOrderId(), e.getReferenceType(), label, MoneyDto.pln(e.getAmount() - e.getRefunded()),
                MoneyDto.pln(e.commissionRemaining()), MoneyDto.pln(e.net()), e.getReleasedAt() != null, e.getRefunded() >= e.getAmount(), e.getCreatedAt());
    }
}
