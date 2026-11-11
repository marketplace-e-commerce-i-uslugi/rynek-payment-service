package pl.rynek.payment.payments;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.payment.clients.OrderClient;
import pl.rynek.payment.clients.OrderClient.OrderInfo;
import pl.rynek.payment.common.Zloty;
import pl.rynek.payment.provider.PaymentProvider;

/**
 * Zwroty pieniędzy kupującemu, zawsze za konkretne zamówienie (płatność z koszyka obejmuje zamówienia kilku
 * sprzedawców). Zwrot blokuje wiersz płatności, więc równoległe zwroty nie przekroczą zapłaconej kwoty.
 * <ul>
 *   <li>Sprzedawca albo admin: {@link #refund} za zamówienie wysłane, zrealizowane albo anulowane, w całości lub w części.
 *   Niewysłane zamówienie się anuluje, a pieniądze wracają automatycznie.</li>
 *   <li>Anulowanie zamówienia w order-service: {@link #orderCancelled} zwraca całą pozostałą kwotę i zamyka płatności
 *   w toku. Klucz {@code cancel:<orderId>} sprawia, że ponowione zgłoszenie nie zwraca pieniędzy drugi raz.</li>
 * </ul>
 * Dostawca {@code sandbox} zwraca od razu. Prawdziwy dostawca rozlicza zwroty asynchronicznie; wtedy zwrot dostanie
 * status i webhook tak jak płatność.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    static final String CANCELLED_REASON = "Zamówienie zostało anulowane.";
    static final String VOIDED_REASON = "Zamówienie zostało anulowane albo wygasło. Nie pobraliśmy pieniędzy.";

    private final PaymentRepository payments;
    private final SellerEarningRepository earnings;
    private final RefundRepository refunds;
    private final OrderClient orders;
    private final PaymentProvider provider;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RefundService(PaymentRepository payments, SellerEarningRepository earnings, RefundRepository refunds, OrderClient orders,
                         PaymentProvider provider, PlatformTransactionManager transactions, Clock clock) {
        this.payments = payments;
        this.earnings = earnings;
        this.refunds = refunds;
        this.orders = orders;
        this.provider = provider;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * Zwrot zlecony przez sprzedawcę zamówienia albo admina. {@code orderId} można pominąć, gdy płatność obejmuje jedno
     * zamówienie wołającego; {@code amount} pominięte oznacza całą pozostałą kwotę. Ponowienie z tym samym
     * Idempotency-Key zwraca płatność bez drugiego zwrotu.
     */
    public Payment refund(UUID callerId, boolean admin, UUID paymentId, String idempotencyKey, UUID orderId, Long amount, String reason) {
        String key = userKey(callerId, idempotencyKey);
        if (key != null) {
            Optional<Refund> earlier = refunds.findByIdempotencyKey(key);
            if (earlier.isPresent()) {
                return payments.findById(earlier.get().getPaymentId()).orElseThrow();
            }
        }
        Payment payment = payments.findById(paymentId).orElseThrow(RefundService::paymentNotFound);
        if (!settled(payment)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ta płatność nie została zaksięgowana, więc nie ma czego zwracać.");
        }
        UUID target = orderId != null ? orderId : soleOrder(payment, callerId, admin);
        if (!payment.getReferences().contains(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ta płatność nie obejmuje tego zamówienia.");
        }
        SellerEarning earning = earning(payment, target);
        if (!admin && !earning.getSellerId().equals(callerId)) {
            throw paymentNotFound();
        }
        OrderInfo order = orders.order(target)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tego zamówienia."));
        if ("TO_SHIP".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Zamówienie " + order.number() + " nie zostało jeszcze wysłane. Anuluj je, a pieniądze wrócą do kupującego automatycznie.");
        }
        String why = reason != null && !reason.isBlank() ? reason.trim()
                : admin && !earning.getSellerId().equals(callerId) ? "Zwrot przez obsługę Rynku." : "Zwrot od sprzedawcy.";
        Payment refunded = apply(paymentId, target, amount, why, callerId, key);
        notifyOrders();
        return refunded;
    }

    /**
     * Zgłasza order-service zwroty od sprzedawcy i admina (zgłoszony przez kupującego zwrot staje się przyjęty). Gdy
     * order-service nie odpowiada, zgłoszenie czeka na następny przebieg.
     */
    @Scheduled(fixedDelayString = "${rynek.payments.refund-notify-retry:60s}", initialDelayString = "${rynek.payments.refund-notify-retry:60s}")
    public void notifyOrders() {
        for (UUID refundId : refunds.findOrderUnnotified()) {
            Refund refund = refunds.findById(refundId).orElseThrow();
            if (!orders.refunded(refund.getOrderId())) {
                log.warn("Zwrot {}: order-service nie odpowiada, ponowimy zgłoszenie", refundId);
                return;
            }
            transaction.executeWithoutResult(status -> refunds.findById(refundId).ifPresent(r -> {
                r.orderNotified();
                refunds.save(r);
            }));
        }
    }

    /**
     * Zamówienie anulowane albo wygasłe (zamiast zdarzenia {@code order.cancelled}): płatności w toku stają się nieudane,
     * a za opłacone zamówienie wraca cała pozostała kwota. Idempotentne. Kolizja z równoległym rozliczeniem płatności
     * daje 503, żeby order-service ponowił zgłoszenie.
     */
    public void orderCancelled(UUID orderId) {
        try {
            transaction.executeWithoutResult(status -> payments.findOpenFor(List.of(orderId))
                    .forEach(p -> p.fail(VOIDED_REASON, clock.instant())));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Płatność właśnie się rozlicza. Spróbuj ponownie.");
        }
        for (Payment payment : payments.findSettledFor(orderId)) {
            if (earning(payment, orderId).refundable() == 0) {
                continue;
            }
            try {
                apply(payment.getId(), orderId, null, CANCELLED_REASON, null, "cancel:" + orderId);
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() != HttpStatus.CONFLICT) {
                    throw e;
                }
                // Równoległy zwrot zdążył oddać resztę kwoty.
            }
        }
    }

    private Payment apply(UUID paymentId, UUID orderId, Long requested, String reason, UUID requestedBy, String key) {
        try {
            return transaction.execute(status -> {
                Payment payment = payments.lockById(paymentId).orElseThrow();
                if (key != null && refunds.findByIdempotencyKey(key).isPresent()) {
                    return payment;
                }
                SellerEarning earning = earnings.findById(orderId).orElseThrow();
                long remaining = earning.refundable();
                if (remaining == 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Pieniądze za to zamówienie zostały już zwrócone.");
                }
                long value = requested == null ? remaining : requested;
                if (value <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kwota zwrotu musi być większa od zera.");
                }
                if (value > remaining) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Możesz zwrócić najwyżej " + Zloty.format(remaining) + ".");
                }
                provider.refund(paymentId, value);
                refunds.save(new Refund(earning, value, reason, requestedBy, key, clock.instant()));
                earning.refund(value);
                payment.refund(value, clock.instant());
                return payments.saveAndFlush(payment);
            });
        } catch (DataIntegrityViolationException e) {
            // Ten sam klucz zapisał właśnie równoległy zwrot.
            if (key != null && refunds.findByIdempotencyKey(key).isPresent()) {
                return payments.findById(paymentId).orElseThrow();
            }
            throw e;
        }
    }

    /**
     * Uzupełnia przychód sprzedawców dla płatności zaksięgowanych przed wprowadzeniem salda, żeby ich zamówienia liczyły
     * się do środków oczekujących i dostępnych. Gdy order-service nie odpowiada, kończy i spróbuje przy następnym przebiegu.
     */
    @Scheduled(fixedDelayString = "${rynek.payments.earnings-backfill:60s}", initialDelayString = "${rynek.payments.earnings-backfill-delay:20s}")
    public void backfillEarnings() {
        for (UUID paymentId : payments.findWithoutEarnings()) {
            Payment payment = payments.findById(paymentId).orElseThrow();
            for (UUID orderId : payment.getReferences()) {
                try {
                    earning(payment, orderId);
                } catch (ResponseStatusException e) {
                    if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                        log.warn("Uzupełnianie przychodu: order-service nie odpowiada, spróbujemy później");
                        return;
                    }
                    log.error("Płatność {}: nie da się uzupełnić przychodu za zamówienie {}: {}", paymentId, orderId, e.getReason());
                }
            }
        }
    }

    /**
     * Przychód z zamówienia. Płatności zaksięgowane przed wprowadzeniem salda go nie mają: wtedy kwotę i sprzedawcę
     * podaje order-service.
     */
    private SellerEarning earning(Payment payment, UUID orderId) {
        Optional<SellerEarning> known = earnings.findById(orderId);
        if (known.isPresent()) {
            return known.get();
        }
        OrderInfo order = orders.order(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tego zamówienia."));
        try {
            return transaction.execute(status -> earnings.saveAndFlush(
                    new SellerEarning(orderId, payment.getId(), order.sellerId(), order.total(), clock.instant())));
        } catch (DataIntegrityViolationException e) {
            return earnings.findById(orderId).orElseThrow(() -> e);
        }
    }

    private UUID soleOrder(Payment payment, UUID callerId, boolean admin) {
        if (payment.getReferences().size() == 1) {
            return payment.getReferences().getFirst();
        }
        List<UUID> candidates = earnings.findByPaymentId(payment.getId()).stream()
                .filter(e -> admin || e.getSellerId().equals(callerId)).map(SellerEarning::getOrderId).toList();
        if (candidates.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wskaż zamówienie (orderId), za które zwracasz pieniądze.");
        }
        return candidates.getFirst();
    }

    private static String userKey(UUID callerId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        if (idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nagłówek Idempotency-Key może mieć najwyżej 100 znaków.");
        }
        return callerId + ":" + idempotencyKey.trim();
    }

    private static boolean settled(Payment payment) {
        return payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.REFUNDED;
    }

    private static ResponseStatusException paymentNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tej płatności.");
    }
}
