package pl.rynek.payment.payouts;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.payment.clients.BookingClient;
import pl.rynek.payment.clients.OrderClient;
import pl.rynek.payment.clients.OrderClient.OrderInfo;
import pl.rynek.payment.payments.ReferenceType;
import pl.rynek.payment.payments.SellerEarningRepository;
import pl.rynek.payment.provider.PaymentProvider;

/**
 * Saldo i wypłaty sprzedawcy.
 * <ul>
 *   <li>Oczekujące: przychód z opłaconych zamówień, które nie są jeszcze zrealizowane, minus zwroty.</li>
 *   <li>Dostępne: przychód z zamówień zrealizowanych minus zwroty i wypłaty. Zwrot po wypłacie może dać saldo ujemne;
 *   wtedy kolejne wypłaty czekają, aż saldo wróci powyżej minimum.</li>
 *   <li>Wypłaty idą automatycznie ({@code rynek.payouts.cron}, domyślnie codziennie o 6:00) na zapisany rachunek.</li>
 * </ul>
 * O realizacji zamówienia (wykonaniu usługi z zaliczką) payment-service dowiaduje się z order-service (booking-service)
 * przy odczycie salda i przed wypłatą, zamiast zdarzeń {@code order.completed} i {@code booking.completed}. Prowizji Rynku
 * jeszcze nie ma (decyzja biznesowa).
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final SellerEarningRepository earnings;
    private final PayoutRepository payouts;
    private final PayoutAccountRepository accounts;
    private final OrderClient orders;
    private final BookingClient bookings;
    private final PaymentProvider provider;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final long minimum;

    public PayoutService(SellerEarningRepository earnings, PayoutRepository payouts, PayoutAccountRepository accounts, OrderClient orders,
                         BookingClient bookings, PaymentProvider provider, PlatformTransactionManager transactions, Clock clock,
                         @Value("${rynek.payouts.minimum:100}") long minimum) {
        this.earnings = earnings;
        this.payouts = payouts;
        this.accounts = accounts;
        this.orders = orders;
        this.bookings = bookings;
        this.provider = provider;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.minimum = minimum;
    }

    public record Balance(long available, long pending) {
    }

    /** Gdy order-service nie odpowiada, saldo jest liczone z ostatnio znanych statusów zamówień. */
    public Balance balance(UUID sellerId) {
        refreshReleases(sellerId);
        return new Balance(available(sellerId), earnings.pending(sellerId));
    }

    public List<Payout> history(UUID sellerId, int offset, int limit) {
        return payouts.findOfSeller(sellerId, offset, limit);
    }

    public PayoutAccount setAccount(UUID sellerId, String holder, String rawIban) {
        String name = holder == null ? "" : holder.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj właściciela rachunku (od 2 do 100 znaków).");
        }
        String iban = Iban.normalize(rawIban).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Numer rachunku jest nieprawidłowy. Sprawdź jego cyfry."));
        return transaction.execute(status -> {
            PayoutAccount account = accounts.lockById(sellerId).orElse(null);
            if (account == null) {
                return accounts.saveAndFlush(new PayoutAccount(sellerId, name, iban, clock.instant()));
            }
            account.change(name, iban, clock.instant());
            return accounts.saveAndFlush(account);
        });
    }

    /** Najpierw rozlicza przelewy w drodze, potem zleca wypłaty sprzedawcom z rachunkiem i dostępnymi środkami. */
    @Scheduled(cron = "${rynek.payouts.cron:0 0 6 * * *}", zone = "${rynek.payouts.zone:Europe/Warsaw}")
    public void run() {
        settleTransfers();
        for (UUID sellerId : accounts.findAllSellerIds()) {
            try {
                payOut(sellerId).ifPresent(p -> log.info("Wypłata {} dla sprzedawcy {}: {} gr", p.getId(), sellerId, p.getAmount()));
            } catch (RuntimeException e) {
                log.error("Wypłata dla sprzedawcy {} się nie udała, spróbujemy przy następnym przebiegu", sellerId, e);
            }
        }
    }

    /** Wypłata całego dostępnego salda, jeśli sprzedawca ma rachunek i saldo jest co najmniej równe minimum. */
    public Optional<Payout> payOut(UUID sellerId) {
        refreshReleases(sellerId);
        return transaction.execute(status -> {
            Optional<PayoutAccount> account = accounts.lockById(sellerId);
            long available = available(sellerId);
            if (account.isEmpty() || available < minimum) {
                return Optional.empty();
            }
            Payout payout = payouts.save(new Payout(sellerId, available, account.get().getIban(), clock.instant()));
            provider.transfer(payout.getId(), account.get().getIban(), available);
            return Optional.of(payout);
        });
    }

    void settleTransfers() {
        for (Payout payout : payouts.findInProgress()) {
            if (provider.transferArrived(payout.getId(), payout.getCreatedAt(), clock.instant())) {
                transaction.executeWithoutResult(status -> payouts.markPaid(payout.getId(), clock.instant()));
            }
        }
    }

    /**
     * Środki z zamówień zrealizowanych i usług wykonanych stają się dostępne. Bez order-service (booking-service) zostają
     * oczekujące do następnego razu.
     */
    private void refreshReleases(UUID sellerId) {
        List<UUID> completed = new java.util.ArrayList<>();
        List<UUID> unreleasedOrders = earnings.findUnreleasedIds(sellerId, ReferenceType.ORDER);
        List<UUID> unreleasedBookings = earnings.findUnreleasedIds(sellerId, ReferenceType.BOOKING);
        try {
            if (!unreleasedOrders.isEmpty()) {
                orders.orders(unreleasedOrders).stream().filter(OrderInfo::completed).map(OrderInfo::id).forEach(completed::add);
            }
        } catch (ResponseStatusException e) {
            log.warn("Saldo sprzedawcy {}: order-service nie odpowiada, statusy zamówień z poprzedniego odczytu", sellerId);
        }
        try {
            if (!unreleasedBookings.isEmpty()) {
                bookings.bookings(unreleasedBookings).stream().filter(BookingClient.BookingInfo::completed)
                        .map(BookingClient.BookingInfo::id).forEach(completed::add);
            }
        } catch (ResponseStatusException e) {
            log.warn("Saldo usługodawcy {}: booking-service nie odpowiada, statusy rezerwacji z poprzedniego odczytu", sellerId);
        }
        if (!completed.isEmpty()) {
            transaction.executeWithoutResult(status -> earnings.release(completed, clock.instant()));
        }
    }

    private long available(UUID sellerId) {
        return earnings.released(sellerId) - payouts.total(sellerId);
    }
}
