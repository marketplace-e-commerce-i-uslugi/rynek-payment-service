package pl.rynek.payment.payments;

/**
 * {@code PENDING}: kupujący płaci u dostawcy (karta, przelew), wynik przyjdzie webhookiem. {@code WAITING_FOR_BLIK}:
 * czeka na kod BLIK. Statusy końcowe: {@code SUCCEEDED}, {@code FAILED}, {@code REFUNDED}.
 */
public enum PaymentStatus {
    PENDING, WAITING_FOR_BLIK, SUCCEEDED, FAILED, REFUNDED;

    public boolean open() {
        return this == PENDING || this == WAITING_FOR_BLIK;
    }
}
