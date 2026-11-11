package pl.rynek.payment.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.payment.payments.RefundService;

@RestController
public class InternalPaymentsController {

    private final RefundService refunds;

    public InternalPaymentsController(RefundService refunds) {
        this.refunds = refunds;
    }

    /**
     * Zamówienie anulowane albo wygasłe (order-service, zamiast zdarzenia {@code order.cancelled}): płatności w toku
     * stają się nieudane, a za opłacone zamówienie wraca cała pozostała kwota. Idempotentne; 503 = ponów. Dostęp: int.
     */
    @PostMapping("/internal/orders/{orderId}/cancelled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void orderCancelled(@PathVariable UUID orderId) {
        refunds.orderCancelled(orderId);
    }
}
