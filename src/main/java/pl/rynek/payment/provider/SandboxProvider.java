package pl.rynek.payment.provider;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Symulator dostawcy bez prawdziwych pieniędzy. BLIK: każdy 6-cyfrowy kod przechodzi poza {@value #REJECTED_BLIK},
 * który bank odrzuca. Karta i przelew: strona {@code /payments/sandbox/{id}} z przyciskami „Zapłać” i „Odrzuć”, która
 * wysyła podpisany webhook jak prawdziwy dostawca.
 */
@Component
@ConditionalOnProperty(name = "rynek.payments.provider", havingValue = SandboxProvider.NAME)
public class SandboxProvider implements PaymentProvider {

    public static final String NAME = "sandbox";
    public static final String REJECTED_BLIK = "000000";

    private final String publicApiUrl;

    public SandboxProvider(@Value("${rynek.payments.public-api-url}") String publicApiUrl) {
        this.publicApiUrl = publicApiUrl;
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
}
