package pl.rynek.payment.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static pl.rynek.payment.common.NotImplemented.notImplemented;

@RestController
public class SellerBalanceController {

    /**
     * Saldo i środki oczekujące. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/balance")
    public void getBalance() {
        throw notImplemented();
    }
}
