package pl.rynek.payment.payments;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Przychód sprzedawcy z jednego opłaconego zamówienia. Oczekujący, dopóki zamówienie nie jest zrealizowane
 * ({@code releasedAt}), potem dostępny do wypłaty. {@code @DynamicUpdate}: zwrot zapisuje tylko {@code refunded},
 * więc nie nadpisze równoległego zwolnienia środków (to idzie osobnym update).
 */
@Entity
@Table(name = "seller_earnings")
@DynamicUpdate
public class SellerEarning {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "seller_id")
    private UUID sellerId;

    private long amount;

    private long refunded;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    protected SellerEarning() {
    }

    SellerEarning(UUID orderId, UUID paymentId, UUID sellerId, long amount, Instant now) {
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.createdAt = now;
    }

    void refund(long value) {
        refunded += value;
    }

    /** Kwota, którą można jeszcze zwrócić kupującemu. */
    public long refundable() {
        return amount - refunded;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public long getAmount() {
        return amount;
    }

    public long getRefunded() {
        return refunded;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
