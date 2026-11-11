package pl.rynek.payment.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Symulator dostawcy bez prawdziwych pieniędzy. BLIK: każdy 6-cyfrowy kod przechodzi poza {@value #REJECTED_BLIK},
 * który bank odrzuca. Karta i przelew: strona {@code /payments/sandbox/{id}} z przyciskami „Zapłać” i „Odrzuć”, która
 * wysyła podpisany webhook jak prawdziwy dostawca. Zwroty przechodzą od razu, a przelew wypłaty „dociera” po
 * {@code rynek.payouts.sandbox-transfer-time} (domyślnie dzień, jak przelew Elixir).
 */
@Component
@ConditionalOnProperty(name = "rynek.payments.provider", havingValue = SandboxProvider.NAME)
public class SandboxProvider implements PaymentProvider {

    public static final String NAME = "sandbox";
    public static final String REJECTED_BLIK = "000000";

    private final String publicApiUrl;
    private final Duration transferTime;

    public SandboxProvider(@Value("${rynek.payments.public-api-url}") String publicApiUrl,
                           @Value("${rynek.payouts.sandbox-transfer-time:1d}") Duration transferTime) {
        this.publicApiUrl = publicApiUrl;
        this.transferTime = transferTime;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String redirectUrl(UUID paymentId) {
        return publicApiUrl + "/payments/sandbox/" + paymentId;
    }

    @Override
    public boolean authorizeBlik(UUID paymentId, long amount, String code) {
        return !REJECTED_BLIK.equals(code);
    }

    @Override
    public void refund(UUID paymentId, long amount) {
    }

    @Override
    public void transfer(UUID payoutId, String iban, long amount) {
    }

    @Override
    public boolean transferArrived(UUID payoutId, Instant orderedAt, Instant now) {
        return !orderedAt.plus(transferTime).isAfter(now);
    }
}
