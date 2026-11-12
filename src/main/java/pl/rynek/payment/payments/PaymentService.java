package pl.rynek.payment.payments;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

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
import pl.rynek.payment.clients.BookingClient;
import pl.rynek.payment.clients.OrderClient;
import pl.rynek.payment.clients.OrderClient.MarkPaid;
import pl.rynek.payment.provider.PaymentProvider;

/**
 * Płatności za zamówienia i zaliczki za rezerwacje. Po udanej płatności zamówienia (rezerwacja) są oznaczane jako opłacone
 * w order-service (booking-service); jeśli serwis nie odpowiada, próba jest ponawiana co minutę ({@link #confirmPending()}).
 * Zaksięgowanie zapisuje przychód każdego sprzedawcy albo usługodawcy ({@link SellerEarning}) do salda.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern BLIK_CODE = Pattern.compile("\\d{6}");
    static final int MAX_REFERENCES = 20;

    private final PaymentRepository payments;
    private final SellerEarningRepository earnings;
    private final RefundService refunds;
    private final OrderClient orders;
    private final BookingClient bookings;
    private final PaymentProvider provider;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, SellerEarningRepository earnings, RefundService refunds, OrderClient orders,
                          BookingClient bookings, PaymentProvider provider, PlatformTransactionManager transactions, Clock clock) {
        this.payments = payments;
        this.earnings = earnings;
        this.refunds = refunds;
        this.orders = orders;
        this.bookings = bookings;
        this.provider = provider;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public PaymentProvider provider() {
        return provider;
    }

    /**
     * {@code referenceId}: id zamówień rozdzielone przecinkami (zakup z koszyka od kilku sprzedawców) albo id jednej
     * rezerwacji (zaliczka). Ponowienie z tym samym Idempotency-Key zwraca tę samą płatność. Nowa płatność zastępuje
     * rozpoczęte i niedokończone płatności za te same zamówienia (rezerwację), więc nie da się zapłacić dwa razy.
     */
    public Payment create(UUID buyerId, String idempotencyKey, ReferenceType type, String referenceIds, PaymentMethod method) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Brak nagłówka Idempotency-Key (do 100 znaków).");
        }
        Optional<Payment> earlier = payments.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        List<UUID> ids = parse(referenceIds);
        if (type == ReferenceType.BOOKING && ids.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zaliczkę płacisz za jedną rezerwację naraz.");
        }
        long amount = 0;
        for (UUID id : ids) {
            Payee payee = payee(type, id).filter(p -> p.buyerId().equals(buyerId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            type == ReferenceType.BOOKING ? "Nie znaleźliśmy tej rezerwacji." : "Nie znaleźliśmy tego zamówienia."));
            if (!payee.awaiting()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, type == ReferenceType.BOOKING
                        ? "Rezerwacja nie czeka już na zaliczkę." : payee.label() + " nie czeka już na płatność.");
            }
            if (payee.amount() <= 0) {
                throw unprocessable("Ta rezerwacja nie wymaga zaliczki.");
            }
            amount += payee.amount();
        }
        Payment payment = new Payment(buyerId, type, ids, amount, method, provider.name(), idempotencyKey, clock.instant());
        try {
            return transaction.execute(status -> {
                payments.findOpenFor(ids).forEach(p -> p.fail("Zastąpiona nową płatnością.", clock.instant()));
                return payments.saveAndFlush(payment);
            });
        } catch (DataIntegrityViolationException e) {
            return payments.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey).orElseThrow(() -> e);
        }
    }

    public Payment get(UUID buyerId, UUID paymentId) {
        return payments.findById(paymentId).filter(p -> p.getBuyerId().equals(buyerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tej płatności."));
    }

    /**
     * Kod BLIK. Odrzucony kod daje 422, a płatność czeka na kolejny (najwyżej {@value Payment#MAX_BLIK_ATTEMPTS} próby).
     */
    public Payment confirmBlik(UUID buyerId, UUID paymentId, String code) {
        Payment payment = get(buyerId, paymentId);
        if (payment.getStatus() != PaymentStatus.WAITING_FOR_BLIK) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, payment.getStatus() == PaymentStatus.SUCCEEDED
                    ? "Ta płatność jest już opłacona." : "Ta płatność nie czeka na kod BLIK. Zacznij płatność od nowa.");
        }
        if (code == null || !BLIK_CODE.matcher(code.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kod BLIK ma 6 cyfr.");
        }
        if (provider.authorizeBlik(paymentId, payment.getAmount(), code.trim())) {
            return settle(paymentId, true, null);
        }
        boolean canRetry = change(paymentId, p -> {
            if (p.getStatus() != PaymentStatus.WAITING_FOR_BLIK) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ta płatność nie czeka już na kod BLIK.");
            }
            return p.rejectBlik(clock.instant());
        });
        throw unprocessable(canRetry ? "Bank odrzucił kod BLIK. Wygeneruj nowy kod i spróbuj ponownie."
                : "Bank odrzucił kod BLIK " + Payment.MAX_BLIK_ATTEMPTS + " razy. Zacznij płatność od nowa.");
    }

    /** Wynik od dostawcy (webhook, strona symulatora). Płatność już rozliczona nie zmienia się (webhook bywa ponawiany). */
    public Payment providerResult(UUID paymentId, boolean succeeded) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tej płatności."));
        if (!payment.getStatus().open()) {
            return payment;
        }
        return settle(paymentId, succeeded, succeeded ? null : "Dostawca płatności odrzucił płatność.");
    }

    /**
     * Rozliczenie płatności w toku. Przed zaksięgowaniem sprawdza, czy zamówienia (rezerwacja) nadal czekają na płatność
     * (mogły wygasnąć albo zostać anulowane); jeśli nie, płatność jest nieudana i pieniądze nie są pobierane.
     */
    private Payment settle(UUID paymentId, boolean succeeded, String reason) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        List<Payee> paid = new ArrayList<>();
        String rejected = succeeded ? notAwaiting(payment, paid) : null;
        Payment settled = change(paymentId, p -> {
            if (!p.getStatus().open()) {
                return p;
            }
            if (!succeeded) {
                p.fail(reason, clock.instant());
            } else if (rejected != null) {
                p.fail(rejected, clock.instant());
            } else {
                p.succeed(clock.instant());
                paid.forEach(o -> earnings.save(new SellerEarning(o.id(), p.getId(), o.sellerId(), o.amount(), p.getReferenceType(), clock.instant())));
            }
            return p;
        });
        if (settled.getStatus() == PaymentStatus.SUCCEEDED) {
            return confirmOrders(settled.getId());
        }
        return settled;
    }

    /** Ponawia oznaczenie zamówień (rezerwacji) jako opłaconych dla udanych płatności, których serwis jeszcze nie przyjął. */
    @Scheduled(fixedDelayString = "${rynek.payments.confirm-retry:60s}", initialDelayString = "${rynek.payments.confirm-retry:60s}")
    public void confirmPending() {
        payments.findUnconfirmed().forEach(this::confirmOrders);
    }

    private Payment confirmOrders(UUID paymentId) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        boolean done = true;
        boolean booking = payment.getReferenceType() == ReferenceType.BOOKING;
        for (UUID referenceId : payment.getReferences()) {
            MarkPaid result = booking ? bookings.markPaid(referenceId, paymentId) : orders.markPaid(referenceId, paymentId);
            if (result == MarkPaid.UNAVAILABLE) {
                done = false;
            } else if (result == MarkPaid.REJECTED) {
                // Anulowano między sprawdzeniem a zaksięgowaniem: pieniądze wracają do płacącego.
                log.warn("Płatność {} zaksięgowana, ale {} odrzuciło mark-paid: zwracamy pieniądze", paymentId, referenceId);
                try {
                    if (booking) {
                        refunds.bookingCancelled(referenceId);
                    } else {
                        refunds.orderCancelled(referenceId);
                    }
                } catch (RuntimeException e) {
                    // Serwis i tak zgłosi anulowanie, a zwrot ma klucz cancel:<id>, więc nie pójdzie dwa razy.
                    log.error("Płatność {}: zwrot za {} się nie udał, ponowi go zgłoszenie anulowania", paymentId, referenceId, e);
                }
            }
        }
        if (!done) {
            log.warn("Płatność {}: serwis zamówień albo rezerwacji nie odpowiada, ponowimy oznaczenie", paymentId);
            return payment;
        }
        return change(paymentId, p -> {
            p.confirmOrders();
            return p;
        });
    }

    /** Powód odrzucenia albo {@code null}, gdy wszystko nadal czeka na płatność; to trafia do {@code awaiting}. */
    private String notAwaiting(Payment payment, List<Payee> awaiting) {
        for (UUID referenceId : payment.getReferences()) {
            Optional<Payee> payee = payee(payment.getReferenceType(), referenceId);
            if (payee.isEmpty() || !payee.get().awaiting()) {
                return payment.getReferenceType() == ReferenceType.BOOKING
                        ? "Rezerwacja została anulowana albo wygasła. Nie pobraliśmy pieniędzy."
                        : payee.map(Payee::label).orElse("Zamówienie") + " zostało anulowane albo wygasło. Nie pobraliśmy pieniędzy.";
            }
            awaiting.add(payee.get());
        }
        return null;
    }

    /** Za co się płaci: zamówienie (kwota zamówienia, sprzedawca) albo rezerwacja (zaliczka, usługodawca). */
    record Payee(UUID id, UUID buyerId, UUID sellerId, long amount, boolean awaiting, String label) {
    }

    private Optional<Payee> payee(ReferenceType type, UUID id) {
        if (type == ReferenceType.BOOKING) {
            return bookings.booking(id).map(b -> new Payee(b.id(), b.customerId(), b.providerId(), b.deposit(), b.awaitingDeposit(),
                    "Rezerwacja „" + b.title() + "”"));
        }
        return orders.order(id).map(o -> new Payee(o.id(), o.buyerId(), o.sellerId(), o.total(), o.awaitingPayment(), "Zamówienie " + o.number()));
    }

    private <T> T change(UUID paymentId, java.util.function.Function<Payment, T> action) {
        try {
            return transaction.execute(status -> {
                Payment payment = payments.findById(paymentId).orElseThrow();
                T result = action.apply(payment);
                payments.saveAndFlush(payment);
                return result;
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Płatność właśnie się zmieniła. Odśwież i spróbuj ponownie.");
        }
    }

    private static List<UUID> parse(String referenceIds) {
        if (referenceIds == null || referenceIds.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj zamówienie albo rezerwację do opłacenia.");
        }
        List<UUID> ids = new ArrayList<>();
        try {
            Arrays.stream(referenceIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(UUID::fromString)
                    .distinct().forEach(ids::add);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowe id zamówienia albo rezerwacji.");
        }
        if (ids.isEmpty() || ids.size() > MAX_REFERENCES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jedna płatność obejmuje od 1 do " + MAX_REFERENCES + " zamówień.");
        }
        return ids;
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
