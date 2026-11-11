package pl.rynek.payment.api;

import java.net.URI;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.payment.common.Zloty;
import pl.rynek.payment.payments.Payment;
import pl.rynek.payment.payments.PaymentRepository;
import pl.rynek.payment.payments.PaymentService;
import pl.rynek.payment.payments.PaymentStatus;
import pl.rynek.payment.provider.SandboxProvider;

/**
 * Strona symulatora dostawcy (tylko {@code rynek.payments.provider=sandbox}): zastępuje stronę banku przy karcie
 * i przelewie. „Zapłać” albo „Odrzuć” rozlicza płatność tak jak webhook dostawcy i wraca na ekran potwierdzenia.
 * <p>
 * {@code <meta name="referrer" content="same-origin">}: gateway wysyła {@code Referrer-Policy: no-referrer}, a przy takiej
 * polityce przeglądarka wysyła formularz z {@code Origin: null}, który filtr CORS gatewaya odrzuca (403). Polityka
 * {@code same-origin} sprawia, że formularz ma prawdziwy Origin gatewaya, czyli żądanie z tego samego źródła.
 */
@RestController
@ConditionalOnProperty(name = "rynek.payments.provider", havingValue = SandboxProvider.NAME)
public class SandboxController {

    private final PaymentRepository repository;
    private final PaymentService payments;
    private final String frontendUrl;

    public SandboxController(PaymentRepository repository, PaymentService payments, @Value("${rynek.payments.frontend-url}") String frontendUrl) {
        this.repository = repository;
        this.payments = payments;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping(value = "/api/v1/payments/sandbox/{paymentId}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable UUID paymentId) {
        Payment payment = find(paymentId);
        String amount = Zloty.format(payment.getAmount());
        String body = payment.getStatus() == PaymentStatus.PENDING
                ? """
                  <form method="post"><button name="result" value="success" class="pay">Zapłać %s</button>
                  <button name="result" value="failure" class="ghost">Odrzuć płatność</button></form>
                  """.formatted(amount)
                : "<p>Ta płatność jest już rozliczona.</p><a href=\"" + returnUrl(payment) + "\">Wróć do Rynku</a>";
        return """
               <!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
               <meta name="referrer" content="same-origin">
               <title>Symulator płatności</title><style>
               body{font:16px/1.5 system-ui,sans-serif;background:#f4f5f7;color:#1b1d21;margin:0;display:grid;place-items:center;min-height:100vh}
               main{background:#fff;border-radius:16px;padding:24px;max-width:360px;width:calc(100%% - 32px);box-shadow:0 2px 12px #0001}
               h1{font-size:20px;margin:0 0 4px}.amount{font-size:28px;font-weight:800;margin:12px 0}.note{font-size:13px;color:#5b6170}
               button{display:block;width:100%%;min-height:48px;border-radius:12px;font:inherit;font-weight:700;margin-top:10px;cursor:pointer}
               .pay{background:#1b1d21;color:#fff;border:0}.ghost{background:none;border:1px solid #c9ccd3}
               </style></head><body><main><h1>Symulator płatności</h1>
               <p class="note">Środowisko testowe: nie pobieramy prawdziwych pieniędzy.</p>
               <p>%s</p><div class="amount">%s</div>%s</main></body></html>
               """.formatted(payment.getMethod().displayName(), amount, body);
    }

    @PostMapping("/api/v1/payments/sandbox/{paymentId}")
    public ResponseEntity<Void> complete(@PathVariable UUID paymentId, @RequestParam String result) {
        Payment payment = payments.providerResult(find(paymentId).getId(), "success".equals(result));
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, returnUrl(payment).toString()).build();
    }

    private URI returnUrl(Payment payment) {
        String orders = payment.getReferences().stream().map(UUID::toString).collect(Collectors.joining(","));
        return URI.create(frontendUrl + "/zamowienie/potwierdzenie?payment=" + payment.getId() + "&orders=" + orders);
    }

    private Payment find(UUID paymentId) {
        return repository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tej płatności."));
    }
}
