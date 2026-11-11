package pl.rynek.payment.payouts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Przelew dostępnych środków na rachunek sprzedawcy z chwili zlecenia. */
@Entity
@Table(name = "payouts")
public class Payout {

    @Id
    private UUID id;

    @Column(name = "seller_id")
    private UUID sellerId;

    private long amount;

    private String iban;

    @Enumerated(EnumType.STRING)
    private PayoutStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected Payout() {
    }

    Payout(UUID sellerId, long amount, String iban, Instant now) {
        this.id = UUID.randomUUID();
        this.sellerId = sellerId;
        this.amount = amount;
        this.iban = iban;
        this.status = PayoutStatus.IN_PROGRESS;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public long getAmount() {
        return amount;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
