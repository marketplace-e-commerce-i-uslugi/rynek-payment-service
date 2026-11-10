package pl.rynek.payment.payments;

/** Kształt {@code PaymentMethod} z rynek-frontend: id, nazwa i krótki opis. */
public enum PaymentMethod {

    BLIK("BLIK", "Kod z aplikacji banku"),
    CARD("Karta płatnicza", "Visa, Mastercard"),
    TRANSFER("Przelew online", "Szybki przelew z Twojego banku");

    private final String displayName;
    private final String description;

    PaymentMethod(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
