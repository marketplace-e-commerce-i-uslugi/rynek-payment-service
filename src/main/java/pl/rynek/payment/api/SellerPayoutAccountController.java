package pl.rynek.payment.api;

import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.payment.api.dto.SetPayoutAccountRequest;

import static pl.rynek.payment.common.NotImplemented.notImplemented;

@RestController
public class SellerPayoutAccountController {

    /**
     * Rachunek do wypłat. Dostęp: seller.
     */
    @PutMapping("/api/v1/seller/payout-account")
    public void setPayoutAccount(@RequestBody SetPayoutAccountRequest request) {
        throw notImplemented();
    }
}
