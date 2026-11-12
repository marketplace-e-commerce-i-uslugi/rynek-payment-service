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

    /** Zamówienie (środki dostępne po realizacji) albo rezerwacja (zaliczka dostępna po wykonaniu usługi). */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "reference_type")
    private ReferenceType referenceType = ReferenceType.ORDER;

    protected SellerEarning() {
    }

    SellerEarning(UUID orderId, UUID paymentId, UUID sellerId, long amount, Instant now) {
        this(orderId, paymentId, sellerId, amount, ReferenceType.ORDER, now);
    }

    /** {@code orderId} to id zamówienia albo rezerwacji (kolumna z czasów, gdy płaciło się tylko za zamówienia). */
    SellerEarning(UUID orderId, UUID paymentId, UUID sellerId, long amount, ReferenceType referenceType, Instant now) {
        this.referenceType = referenceType;
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
