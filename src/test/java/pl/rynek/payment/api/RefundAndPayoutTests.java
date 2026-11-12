package pl.rynek.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import pl.rynek.payment.TestcontainersConfig;
import pl.rynek.payment.clients.BookingClient;
import pl.rynek.payment.clients.OrderClient;
import pl.rynek.payment.clients.OrderClient.MarkPaid;
import pl.rynek.payment.clients.OrderClient.OrderInfo;
import pl.rynek.payment.payments.RefundRepository;
import pl.rynek.payment.payments.RefundService;
import pl.rynek.payment.payments.SellerEarningRepository;
import pl.rynek.payment.payouts.PayoutService;

/**
 * Zwroty, saldo i wypłaty na prawdziwym PostgreSQL. Atrapa order-service trzyma zamówienia w mapie, więc test zmienia
 * ich status (wysłane, zrealizowane, anulowane) jak sprzedawca. Przelew wypłaty w symulatorze dociera od razu.
 */
@SpringBootTest(properties = {"rynek.payments.confirm-retry=1h", "rynek.payments.earnings-backfill=1h", "rynek.payments.refund-notify-retry=1h", "rynek.payouts.cron=-", "rynek.payouts.sandbox-transfer-time=0s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RefundAndPayoutTests {

    static final String IBAN = "PL61 1090 1014 0000 0712 1981 2874";

    @Autowired
    MockMvc mvc;

    @Autowired
    PayoutService payouts;

    @Autowired
    RefundRepository refundRows;

    @Autowired
    RefundService refunds;

    @Autowired
    SellerEarningRepository earnings;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    OrderClient orders;

    @MockitoBean
    BookingClient bookingClient;

    final Map<UUID, OrderInfo> known = new ConcurrentHashMap<>();
    volatile MarkPaid markPaidResult = MarkPaid.OK;

    @BeforeEach
    void stubs() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> {
            String token = call.getArgument(0);
            boolean admin = token.startsWith("admin-");
            if (!token.startsWith("user-") && !admin) {
                throw new BadJwtException("Nieważny token");
            }
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token.substring(token.indexOf('-') + 1))
                    .claim("roles", admin ? List.of("ADMIN", "BUYER") : List.of("BUYER"))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        });
        given(orders.order(any())).willAnswer(call -> Optional.ofNullable(known.get(call.<UUID>getArgument(0))));
        given(orders.refunded(any())).willReturn(true);
        given(orders.orders(any())).willAnswer(call -> call.<List<UUID>>getArgument(0).stream().map(known::get)
                .filter(o -> o != null).toList());
        given(orders.markPaid(any(), any())).willAnswer(call -> {
            if (markPaidResult == MarkPaid.OK) {
                setStatus(call.getArgument(0), "TO_SHIP");
            } else if (markPaidResult == MarkPaid.REJECTED) {
                setStatus(call.getArgument(0), "CANCELLED");
            }
            return markPaidResult;
        });
    }

    @Test
    void sellerRefundsShippedOrderInPartsAndBalanceFollows() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID a = order(buyer, seller, 10000);
        UUID b = order(buyer, other, 5000);
        String payment = paidWithBlik(buyer, a + "," + b);
        balance(seller).andExpect(jsonPath("$.pending.amount").value(10000)).andExpect(jsonPath("$.available.amount").value(0));

        // Niewysłane zamówienie się anuluje, zamiast zwracać pieniądze ręcznie.
        refund(seller, payment, null, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Zamówienie #" + a.toString().substring(0, 4)
                        + " nie zostało jeszcze wysłane. Anuluj je, a pieniądze wrócą do kupującego automatycznie."));
        setStatus(a, "SHIPPED");
        // Sprzedawca B nie zwraca pieniędzy za zamówienie A; kupujący w ogóle nie zwraca.
        refund(other, payment, null, "{\"orderId\":\"" + a + "\"}").andExpect(status().isNotFound());
        refund(buyer, payment, null, "{\"orderId\":\"" + a + "\"}").andExpect(status().isNotFound());

        refund(seller, payment, "r1", "{\"amount\":{\"amount\":3000,\"currency\":\"PLN\"},\"reason\":\"Rysa na obudowie\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refunded.amount").value(3000));
        // Ponowienie z tym samym kluczem nie zwraca drugi raz.
        refund(seller, payment, "r1", "{\"amount\":{\"amount\":3000,\"currency\":\"PLN\"}}").andExpect(jsonPath("$.refunded.amount").value(3000));
        refund(seller, payment, null, "{\"amount\":{\"amount\":8000,\"currency\":\"PLN\"}}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Możesz zwrócić najwyżej 70,00 zł."));
        refund(seller, payment, null, "{\"amount\":{\"amount\":0,\"currency\":\"PLN\"}}").andExpect(status().isBadRequest());
        balance(seller).andExpect(jsonPath("$.pending.amount").value(7000));

        setStatus(a, "COMPLETED");
        balance(seller).andExpect(jsonPath("$.pending.amount").value(0)).andExpect(jsonPath("$.available.amount").value(7000));
        balance(other).andExpect(jsonPath("$.pending.amount").value(5000));

        refund(seller, payment, null, null).andExpect(status().isOk()).andExpect(jsonPath("$.refunded.amount").value(10000))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        refund(seller, payment, null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Pieniądze za to zamówienie zostały już zwrócone."));
        balance(seller).andExpect(jsonPath("$.available.amount").value(0));
        mvc.perform(get("/api/v1/payments/" + payment).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.refunded.amount").value(10000));
    }

    @Test
    void sellerRefundIsReportedToOrderServiceButCancellationRefundIsNot() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID returned = order(buyer, seller, 6000);
        String payment = paidWithBlik(buyer, returned.toString());
        setStatus(returned, "COMPLETED");
        // order-service nie odpowiada: zwrot się udaje, a zgłoszenie czeka.
        given(orders.refunded(returned)).willReturn(false);
        refund(seller, payment, null, "{}").andExpect(status().isOk());
        verify(orders, times(1)).refunded(returned);
        given(orders.refunded(returned)).willReturn(true);
        refunds.notifyOrders();
        refunds.notifyOrders();
        verify(orders, times(2)).refunded(returned);

        UUID cancelled = order(buyer, seller, 4000);
        paidWithBlik(buyer, cancelled.toString());
        setStatus(cancelled, "CANCELLED");
        mvc.perform(post("/internal/orders/" + cancelled + "/cancelled")).andExpect(status().isNoContent());
        refunds.notifyOrders();
        verify(orders, never()).refunded(cancelled);
    }

    @Test
    void adminRefundsAnyOrderAndWholePaymentBecomesRefunded() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, UUID.randomUUID(), 4900);
        String payment = paidWithBlik(buyer, a.toString());
        setStatus(a, "COMPLETED");
        mvc.perform(post("/api/v1/payments/" + payment + "/refund").header(HttpHeaders.AUTHORIZATION, "Bearer admin-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refunded.amount").value(4900));
    }

    @Test
    void cancelledOrderIsRefundedOnceAndOpenPaymentsFail() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID paid = order(buyer, seller, 12900);
        String payment = paidWithBlik(buyer, paid.toString());
        setStatus(paid, "CANCELLED");
        mvc.perform(post("/internal/orders/" + paid + "/cancelled")).andExpect(status().isNoContent());
        mvc.perform(post("/internal/orders/" + paid + "/cancelled")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/payments/" + payment).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refunded.amount").value(12900));
        assertThat(refundRows.findByIdempotencyKey("cancel:" + paid)).isPresent();
        balance(seller).andExpect(jsonPath("$.pending.amount").value(0)).andExpect(jsonPath("$.available.amount").value(0));

        // Wygasłe zamówienie: płatność czekająca na BLIK staje się nieudana.
        UUID unpaid = order(buyer, seller, 5000);
        String open = id(pay(buyer, "k-open", unpaid.toString()));
        setStatus(unpaid, "CANCELLED");
        mvc.perform(post("/internal/orders/" + unpaid + "/cancelled")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/payments/" + open).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("Zamówienie zostało anulowane albo wygasło. Nie pobraliśmy pieniędzy."));
    }

    @Test
    void orderCancelledDuringPaymentIsRefundedAutomatically() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, UUID.randomUUID(), 7700);
        String payment = id(pay(buyer, "k1", a.toString()));
        markPaidResult = MarkPaid.REJECTED;
        blik(buyer, payment).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/" + payment).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void paymentsFromBeforeBalanceGetSellerEarningsBackfilled() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        UUID a = order(buyer, seller, 8800);
        paidWithBlik(buyer, a.toString());
        // Płatność sprzed salda: zaksięgowana bez przychodu sprzedawcy.
        earnings.deleteById(a);
        balance(seller).andExpect(jsonPath("$.pending.amount").value(0));

        refunds.backfillEarnings();
        balance(seller).andExpect(jsonPath("$.pending.amount").value(8800));
        refunds.backfillEarnings();
        assertThat(earnings.findById(a)).hasValueSatisfying(e -> assertThat(e.getAmount()).isEqualTo(8800));
    }

    @Test
    void payoutAccountIsValidatedAndAvailableFundsArePaidOut() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        account(seller, "{\"holder\":\"Elektro-Market\",\"iban\":\"PL61 1090 1014 0000 0712 1981 2875\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Numer rachunku jest nieprawidłowy. Sprawdź jego cyfry."));
        account(seller, "{\"holder\":\" \",\"iban\":\"" + IBAN + "\"}").andExpect(status().isBadRequest());
        // Sam numer krajowy dostaje prefiks PL.
        account(seller, "{\"holder\":\"Elektro-Market  Sp. z o.o.\",\"iban\":\"61109010140000071219812874\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holder").value("Elektro-Market Sp. z o.o."))
                .andExpect(jsonPath("$.iban").value(IBAN));

        UUID done = order(buyer, seller, 20000);
        UUID inTransit = order(buyer, seller, 3000);
        paidWithBlik(buyer, done + "," + inTransit);
        setStatus(done, "COMPLETED");
        setStatus(inTransit, "SHIPPED");
        payouts.run();

        balance(seller).andExpect(jsonPath("$.available.amount").value(0)).andExpect(jsonPath("$.pending.amount").value(3000));
        payoutList(seller).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].amount.amount").value(20000))
                .andExpect(jsonPath("$.items[0].status").value("W realizacji"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        // Następny przebieg: przelew dotarł, nowej wypłaty nie ma (saldo 0).
        payouts.run();
        payoutList(seller).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].status").value("Wypłacono"));

        // Bez rachunku nie ma wypłaty.
        UUID noAccount = UUID.randomUUID();
        UUID c = order(buyer, noAccount, 1500);
        paidWithBlik(buyer, c.toString());
        setStatus(c, "COMPLETED");
        payouts.run();
        payoutList(noAccount).andExpect(jsonPath("$.items", hasSize(0)));
        balance(noAccount).andExpect(jsonPath("$.available.amount").value(1500));
    }

    private UUID order(UUID buyer, UUID seller, long total) {
        UUID id = UUID.randomUUID();
        known.put(id, new OrderInfo(id, "#" + id.toString().substring(0, 4), buyer, seller, "AWAITING_PAYMENT", total));
        return id;
    }

    private void setStatus(UUID orderId, String status) {
        OrderInfo o = known.get(orderId);
        known.put(orderId, new OrderInfo(orderId, o.number(), o.buyerId(), o.sellerId(), status, o.total()));
    }

    private String paidWithBlik(UUID buyer, String orderIds) throws Exception {
        String id = id(pay(buyer, UUID.randomUUID().toString(), orderIds));
        blik(buyer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        return id;
    }

    private ResultActions pay(UUID buyer, String key, String orderIds) throws Exception {
        return mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"referenceType\":\"ORDER\",\"referenceId\":\"" + orderIds + "\",\"method\":\"BLIK\"}"));
    }

    private ResultActions blik(UUID buyer, String paymentId) throws Exception {
        return mvc.perform(post("/api/v1/payments/" + paymentId + "/blik").header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"));
    }

    private ResultActions refund(UUID user, String paymentId, String key, String body) throws Exception {
        var request = post("/api/v1/payments/" + paymentId + "/refund").header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private ResultActions balance(UUID seller) throws Exception {
        return mvc.perform(get("/api/v1/seller/balance").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());
    }

    private ResultActions payoutList(UUID seller) throws Exception {
        return mvc.perform(get("/api/v1/seller/payouts").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());
    }

    private ResultActions account(UUID seller, String body) throws Exception {
        return mvc.perform(put("/api/v1/seller/payout-account").header(HttpHeaders.AUTHORIZATION, bearer(seller))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String bearer(UUID userId) {
        return "Bearer user-" + userId;
    }
}
