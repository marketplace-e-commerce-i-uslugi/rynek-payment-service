package pl.rynek.payment.api;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.payment.api.dto.ConfirmBlikRequest;
import pl.rynek.payment.api.dto.CreatePaymentRequest;
import pl.rynek.payment.api.dto.PaymentMethodResponse;
import pl.rynek.payment.api.dto.PaymentResponse;
import pl.rynek.payment.api.dto.RefundRequest;
import pl.rynek.payment.api.dto.WebhookEvent;
import pl.rynek.payment.payments.PaymentMethod;
import pl.rynek.payment.payments.PaymentService;
import pl.rynek.payment.payments.RefundService;
import pl.rynek.payment.provider.WebhookSignature;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class PaymentsController {

    private final PaymentService payments;
    private final RefundService refunds;
    private final WebhookSignature signature;
    private final ObjectMapper json;

    public PaymentsController(PaymentService payments, RefundService refunds, WebhookSignature signature, ObjectMapper json) {
        this.payments = payments;
        this.refunds = refunds;
        this.signature = signature;
        this.json = json;
    }

    /**
     * Dostępne metody płatności. Dostęp: user.
     */
    @GetMapping("/api/v1/payments/methods")
    public List<PaymentMethodResponse> listPaymentMethods() {
        return Arrays.stream(PaymentMethod.values()).map(PaymentMethodResponse::of).toList();
    }

    /**
     * Rozpoczęcie płatności za zamówienie lub zaliczkę rezerwacji ({@code referenceType}: ORDER / BOOKING, {@code Idempotency-Key}).
     * Dostęp: user. BLIK czeka na kod ({@code WAITING_FOR_BLIK}), karta i przelew zwracają {@code redirectUrl} dostawcy.
     */
    @PostMapping("/api/v1/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse createPayment(@AuthenticationPrincipal Jwt token,
                                         @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                         @Valid @RequestBody CreatePaymentRequest request) {
        return view(payments.create(userId(token), idempotencyKey, request.referenceType(), request.referenceId(), request.method()));
    }

    /**
     * Potwierdzenie kodem BLIK. Dostęp: user. Odrzucony kod: 422, płatność czeka na kolejny kod.
     */
    @PostMapping("/api/v1/payments/{paymentId}/blik")
    public PaymentResponse confirmBlik(@AuthenticationPrincipal Jwt token, @PathVariable UUID paymentId,
                                       @Valid @RequestBody ConfirmBlikRequest request) {
        return view(payments.confirmBlik(userId(token), paymentId, request.code()));
    }

    /**
     * Status płatności. Dostęp: user (tylko płacący).
     */
    @GetMapping("/api/v1/payments/{paymentId}")
    public PaymentResponse getPayment(@AuthenticationPrincipal Jwt token, @PathVariable UUID paymentId) {
        return view(payments.get(userId(token), paymentId));
    }

    /**
     * Webhook dostawcy płatności (weryfikacja podpisu {@code X-Signature}). Dostęp: pub (podpis). Ponowiony webhook dla
     * rozliczonej płatności niczego nie zmienia.
     */
    @PostMapping("/api/v1/payments/webhooks/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleProviderWebhook(@PathVariable String provider, @RequestHeader(name = WebhookSignature.HEADER, required = false) String sig,
                                      @RequestBody String payload) {
        if (!provider.equals(payments.provider().name())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nieznany dostawca płatności.");
        }
        if (!signature.valid(payload, sig)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nieprawidłowy podpis webhooka.");
        }
        WebhookEvent event;
        try {
            event = json.readValue(payload, WebhookEvent.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowa treść webhooka.");
        }
        if (event.paymentId() == null || !("SUCCEEDED".equals(event.result()) || "FAILED".equals(event.result()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowa treść webhooka.");
        }
        payments.providerResult(event.paymentId(), "SUCCEEDED".equals(event.result()));
    }

    /**
     * Zwrot środków za zamówienie wysłane, zrealizowane albo anulowane, w całości lub w części. Dostęp: seller (sprzedawca
     * zamówienia) / admin. Za niewysłane zamówienie jest 409: wystarczy je anulować, a pieniądze wrócą automatycznie.
     * {@code Idempotency-Key} jest opcjonalny: ponowienie z tym samym kluczem nie zwraca pieniędzy drugi raz.
     */
    @PostMapping("/api/v1/payments/{paymentId}/refund")
    public PaymentResponse refund(@AuthenticationPrincipal Jwt token, @PathVariable UUID paymentId,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody(required = false) RefundRequest request) {
        RefundRequest body = request == null ? new RefundRequest(null, null, null) : request;
        Long amount = null;
        if (body.amount() != null) {
            if (body.amount().currency() != null && !"PLN".equals(body.amount().currency())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zwrot jest możliwy tylko w złotych (PLN).");
            }
            amount = body.amount().amount();
        }
        List<String> roles = token.getClaimAsStringList("roles");
        boolean admin = roles != null && roles.contains("ADMIN");
        return view(refunds.refund(userId(token), admin, paymentId, idempotencyKey, body.orderId(), amount, body.reason()));
    }

    private PaymentResponse view(pl.rynek.payment.payments.Payment payment) {
        return PaymentResponse.of(payment, payments.provider());
    }

    private static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}
