package pl.rynek.payment.api;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.payment.api.dto.BalanceResponse;
import pl.rynek.payment.api.dto.CursorPage;
import pl.rynek.payment.api.dto.EarningResponse;
import pl.rynek.payment.payouts.PayoutService;

@RestController
public class SellerBalanceController {

    private final PayoutService payouts;
    private final double commissionPercent;
    private final pl.rynek.payment.clients.OrderClient orders;
    private final pl.rynek.payment.clients.BookingClient bookings;

    public SellerBalanceController(PayoutService payouts, pl.rynek.payment.clients.OrderClient orders, pl.rynek.payment.clients.BookingClient bookings,
                                   @Value("${rynek.payments.commission-percent:10}") double commissionPercent) {
        this.orders = orders;
        this.bookings = bookings;
        this.payouts = payouts;
        this.commissionPercent = commissionPercent;
    }

    /**
     * Saldo i środki oczekujące. Dostęp: seller. Oczekujące: opłacone zamówienia jeszcze niezrealizowane; dostępne:
     * zrealizowane minus zwroty i wypłaty.
     */
    @GetMapping("/api/v1/seller/balance")
    public BalanceResponse getBalance(@AuthenticationPrincipal Jwt token) {
        return BalanceResponse.of(payouts.balance(UUID.fromString(token.getSubject())), commissionPercent);
    }

    /**
     * Przychody z zamówień i rezerwacji, od najnowszego: kwota od kupującego, prowizja platformy i kwota dla sprzedawcy. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/earnings")
    public CursorPage<EarningResponse> listEarnings(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit) {
        UUID seller = UUID.fromString(token.getSubject());
        return Pages.page(cursor, limit, (offset, n) -> labelled(payouts.earnings(seller, offset, n)), e -> e);
    }

    /** Podpisy („Zamówienie #1078”) pobieramy zbiorczo; gdy serwis nie odpowiada, lista wraca bez podpisów. */
    private java.util.List<EarningResponse> labelled(java.util.List<pl.rynek.payment.payments.SellerEarning> rows) {
        java.util.Map<UUID, String> labels = new java.util.HashMap<>();
        try {
            var orderIds = rows.stream().filter(e -> e.getReferenceType() == pl.rynek.payment.payments.ReferenceType.ORDER).map(pl.rynek.payment.payments.SellerEarning::getOrderId).toList();
            var bookingIds = rows.stream().filter(e -> e.getReferenceType() == pl.rynek.payment.payments.ReferenceType.BOOKING).map(pl.rynek.payment.payments.SellerEarning::getOrderId).toList();
            if (!orderIds.isEmpty()) {
                orders.orders(orderIds).forEach(o -> labels.put(o.id(), "Zamówienie " + o.number()));
            }
            if (!bookingIds.isEmpty()) {
                bookings.bookings(bookingIds).forEach(b -> labels.put(b.id(), "Rezerwacja „" + b.title() + "”"));
            }
        } catch (RuntimeException unavailable) {
            // Podpisy są dodatkiem do kwot.
        }
        return rows.stream().map(e -> EarningResponse.of(e, labels.get(e.getOrderId()))).toList();
    }
}
