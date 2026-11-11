package pl.rynek.payment.payments;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Zwrot pieniędzy kupującemu za jedno zamówienie (w całości albo w części). */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "seller_id")
    private UUID sellerId;

    private long amount;

    private String reason;

    /** Sprzedawca albo admin; {@code null}: zwrot automatyczny po anulowaniu zamówienia. */
    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private Instant createdAt;

    /** {@code false}: order-service jeszcze nie wie o zwrocie (dotyczy zwrotów od sprzedawcy i admina). */
    @Column(name = "order_notified")
    private boolean orderNotified;

    protected Refund() {
    }

    Refund(SellerEarning earning, long amount, String reason, UUID requestedBy, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.paymentId = earning.getPaymentId();
        this.orderId = earning.getOrderId();
        this.sellerId = earning.getSellerId();
        this.amount = amount;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.orderNotified = requestedBy == null;
    }

    void orderNotified() {
        this.orderNotified = true;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public long getAmount() {
        return amount;
    }
}
