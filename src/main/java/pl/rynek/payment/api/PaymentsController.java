package pl.rynek.payment.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.payment.api.dto.ConfirmBlikRequest;
import pl.rynek.payment.api.dto.CreatePaymentRequest;
import pl.rynek.payment.api.dto.RefundRequest;

import static pl.rynek.payment.common.NotImplemented.notImplemented;

@RestController
public class PaymentsController {

    /**
     * Dostępne metody płatności. Dostęp: user.
     */
    @GetMapping("/api/v1/payments/methods")
    public void listPaymentMethods() {
        throw notImplemented();
    }

    /**
     * Rozpoczęcie płatności za zamówienie lub zaliczkę rezerwacji (referenceType: ORDER / BOOKING, Idempotency-Key). Dostęp: user.
     */
    @PostMapping("/api/v1/payments")
    public void createPayment(@RequestBody CreatePaymentRequest request) {
        throw notImplemented();
    }

    /**
     * Potwierdzenie kodem BLIK. Dostęp: user.
     */
    @PostMapping("/api/v1/payments/{paymentId}/blik")
    public void confirmBlik(@PathVariable String paymentId, @RequestBody ConfirmBlikRequest request) {
        throw notImplemented();
    }

    /**
     * Status płatności. Dostęp: user.
     */
    @GetMapping("/api/v1/payments/{paymentId}")
    public void getPayment(@PathVariable String paymentId) {
        throw notImplemented();
    }

    /**
     * Webhook dostawcy płatności (weryfikacja podpisu). Dostęp: pub (podpis).
     */
    @PostMapping("/api/v1/payments/webhooks/{provider}")
    public void handleProviderWebhook(@PathVariable String provider, @RequestBody String payload) {
        throw notImplemented();
    }

    /**
     * Zwrot środków. Dostęp: seller / admin.
     */
    @PostMapping("/api/v1/payments/{paymentId}/refund")
    public void refund(@PathVariable String paymentId, @RequestBody RefundRequest request) {
        throw notImplemented();
    }
}
