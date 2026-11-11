package pl.rynek.payment.payouts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Rachunek do wypłat sprzedawcy. */
@Entity
@Table(name = "payout_accounts")
public class PayoutAccount {

    @Id
    @Column(name = "seller_id")
    private UUID sellerId;

    private String holder;

    private String iban;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PayoutAccount() {
    }

    PayoutAccount(UUID sellerId, String holder, String iban, Instant now) {
        this.sellerId = sellerId;
        this.holder = holder;
        this.iban = iban;
        this.updatedAt = now;
    }

    void change(String holder, String iban, Instant now) {
        this.holder = holder;
        this.iban = iban;
        this.updatedAt = now;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public String getHolder() {
        return holder;
    }

    public String getIban() {
        return iban;
    }

    /** IBAN w grupach po 4 znaki, jak na wyciągu. */
    public String getFormattedIban() {
        return Iban.format(iban);
    }
}
