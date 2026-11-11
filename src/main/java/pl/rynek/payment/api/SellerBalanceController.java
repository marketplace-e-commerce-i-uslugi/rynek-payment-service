package pl.rynek.payment.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.payment.api.dto.BalanceResponse;
import pl.rynek.payment.payouts.PayoutService;

@RestController
public class SellerBalanceController {

    private final PayoutService payouts;

    public SellerBalanceController(PayoutService payouts) {
        this.payouts = payouts;
    }

    /**
     * Saldo i środki oczekujące. Dostęp: seller. Oczekujące: opłacone zamówienia jeszcze niezrealizowane; dostępne:
     * zrealizowane minus zwroty i wypłaty.
     */
    @GetMapping("/api/v1/seller/balance")
    public BalanceResponse getBalance(@AuthenticationPrincipal Jwt token) {
        return BalanceResponse.of(payouts.balance(UUID.fromString(token.getSubject())));
    }
}
