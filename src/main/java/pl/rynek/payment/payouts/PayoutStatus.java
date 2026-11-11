package pl.rynek.payment.payouts;

import com.fasterxml.jackson.annotation.JsonValue;

/** W API polskie etykiety ({@code Payout.status} z rynek-frontend), w bazie nazwy stałych. */
public enum PayoutStatus {

    IN_PROGRESS("W realizacji"),
    PAID("Wypłacono");

    private final String label;

    PayoutStatus(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }
}
