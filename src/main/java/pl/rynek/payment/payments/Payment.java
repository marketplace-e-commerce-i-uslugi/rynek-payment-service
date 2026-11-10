package pl.rynek.payment.payments;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/**
 * Płatność za jedno lub kilka zamówień. {@code @Version}: webhook dostawcy i ponowione potwierdzenie BLIK w tej
 * samej chwili nie zaksięgują płatności dwa razy.
 */
@Entity
@Table(name = "payments")
public class Payment {

    static final int MAX_BLIK_ATTEMPTS = 3;

    @Id
    private UUID id;

    @Column(name = "buyer_id")
    private UUID buyerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type")
    private ReferenceType referenceType;

    private long amount;

    private String currency;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    private String provider;

    @Column(name = "blik_attempts")
    private int blikAttempts;

    @Column(name = "orders_confirmed")
    private boolean ordersConfirmed;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private int version;

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "payment_references", joinColumns = @JoinColumn(name = "payment_id"))
    @OrderColumn(name = "position")
    @Column(name = "reference_id")
    private List<UUID> references = new ArrayList<>();

    protected Payment() {
    }

    Payment(UUID buyerId, ReferenceType referenceType, List<UUID> references, long amount, PaymentMethod method, String provider,
            String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.buyerId = buyerId;
        this.referenceType = referenceType;
        this.references = new ArrayList<>(references);
        this.amount = amount;
        this.currency = "PLN";
        this.method = method;
        this.provider = provider;
        this.idempotencyKey = idempotencyKey;
        this.status = method == PaymentMethod.BLIK ? PaymentStatus.WAITING_FOR_BLIK : PaymentStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void succeed(Instant now) {
        move(PaymentStatus.SUCCEEDED, null, now);
    }

    void fail(String reason, Instant now) {
        move(PaymentStatus.FAILED, reason, now);
    }

    /** Odrzucony kod BLIK; po {@value #MAX_BLIK_ATTEMPTS} próbach płatność jest nieudana. Zwraca, czy można próbować dalej. */
    boolean rejectBlik(Instant now) {
        blikAttempts++;
        updatedAt = now;
        if (blikAttempts >= MAX_BLIK_ATTEMPTS) {
            fail("Bank odrzucił kod BLIK " + MAX_BLIK_ATTEMPTS + " razy.", now);
            return false;
        }
        return true;
    }

    void confirmOrders() {
        this.ordersConfirmed = true;
    }

    private void move(PaymentStatus next, String reason, Instant now) {
        this.status = next;
        this.statusReason = reason;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public ReferenceType getReferenceType() {
        return referenceType;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public String getProvider() {
        return provider;
    }

    public boolean isOrdersConfirmed() {
        return ordersConfirmed;
    }

    public List<UUID> getReferences() {
        return List.copyOf(references);
    }
}
