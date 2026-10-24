package pl.rynek.payment.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static pl.rynek.payment.common.NotImplemented.notImplemented;

@RestController
public class SellerPayoutsController {

    /**
     * Historia wypłat. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/payouts")
    public void listPayouts() {
        throw notImplemented();
    }
}
