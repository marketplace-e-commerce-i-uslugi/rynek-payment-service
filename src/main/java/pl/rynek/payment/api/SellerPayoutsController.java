package pl.rynek.payment.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.payment.api.dto.CursorPage;
import pl.rynek.payment.api.dto.PayoutResponse;
import pl.rynek.payment.payouts.PayoutService;

@RestController
public class SellerPayoutsController {

    private final PayoutService payouts;

    public SellerPayoutsController(PayoutService payouts) {
        this.payouts = payouts;
    }

    /**
     * Historia wypłat, od najnowszej. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/payouts")
    public CursorPage<PayoutResponse> listPayouts(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        UUID seller = UUID.fromString(token.getSubject());
        return Pages.page(cursor, limit, (offset, n) -> payouts.history(seller, offset, n), PayoutResponse::of);
    }
}
