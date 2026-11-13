package pl.rynek.payment.api;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.payment.api.dto.PayoutAccountResponse;
import pl.rynek.payment.api.dto.SetPayoutAccountRequest;
import pl.rynek.payment.payouts.PayoutService;

@RestController
public class SellerPayoutAccountController {

    private final PayoutService payouts;

    public SellerPayoutAccountController(PayoutService payouts) {
        this.payouts = payouts;
    }

    /**
     * Zapisany rachunek do wypłat (endpoint spoza dokumentu architektury). 404, gdy sprzedawca go nie dodał. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/payout-account")
    public PayoutAccountResponse getPayoutAccount(@AuthenticationPrincipal Jwt token) {
        return PayoutAccountResponse.of(payouts.account(UUID.fromString(token.getSubject())));
    }

    /**
     * Rachunek do wypłat (IBAN z sumą kontrolną). Dostęp: seller.
     */
    @PutMapping("/api/v1/seller/payout-account")
    public PayoutAccountResponse setPayoutAccount(@AuthenticationPrincipal Jwt token, @Valid @RequestBody SetPayoutAccountRequest request) {
        return PayoutAccountResponse.of(payouts.setAccount(UUID.fromString(token.getSubject()), request.holder(), request.iban()));
    }
}
