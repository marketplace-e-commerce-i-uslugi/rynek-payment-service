package pl.rynek.payment.provider;

import java.time.Instant;
import java.util.UUID;

/**
 * Dostawca płatności (§ README: Przelewy24 / PayU / Stripe, decyzja biznesowa). Dane kart nigdy nie przechodzą przez
 * Rynek: karta i przelew kończą się u dostawcy, a wynik przychodzi webhookiem.
 */
public interface PaymentProvider {

    /** Nazwa w ścieżce webhooka {@code /payments/webhooks/{provider}}. */
    String name();

    /** Adres strony dostawcy, na którą przeglądarka przechodzi przy karcie i przelewie. */
    String redirectUrl(UUID paymentId);

    /** Autoryzacja kodu BLIK u dostawcy: {@code true} = bank zaakceptował. */
    boolean authorizeBlik(UUID paymentId, long amount, String code);

    /** Zwrot części albo całości zaksięgowanej płatności na rachunek kupującego. Błąd dostawcy: wyjątek. */
    void refund(UUID paymentId, long amount);

    /** Zlecenie przelewu wypłaty na rachunek sprzedawcy. Błąd dostawcy: wyjątek. */
    void transfer(UUID payoutId, String iban, long amount);

    /** Czy zlecony przelew wypłaty dotarł na rachunek (status „Wypłacono”). */
    boolean transferArrived(UUID payoutId, Instant orderedAt, Instant now);
}
