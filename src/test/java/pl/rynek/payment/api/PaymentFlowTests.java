package pl.rynek.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import pl.rynek.payment.payments.PaymentRepository;
import pl.rynek.payment.payments.PaymentService;
import pl.rynek.payment.provider.WebhookSignature;

/**
 * Płatności na prawdziwym PostgreSQL z dostawcą „sandbox”. Atrapa order-service trzyma zamówienia w mapie, więc test
 * może je anulować albo udawać, że order-service nie odpowiada.
 */
@SpringBootTest(properties = {"rynek.payments.commission-percent=0", "rynek.payments.confirm-retry=1h", "rynek.payments.earnings-backfill=1h", "rynek.payments.refund-notify-retry=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PaymentFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    WebhookSignature signature;

    @Autowired
    PaymentService service;

    @Autowired
    PaymentRepository repository;

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
            if (!token.startsWith("user-")) {
                throw new BadJwtException("Nieważny token");
            }
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token.substring(5))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        });
        given(orders.order(any())).willAnswer(call -> Optional.ofNullable(known.get(call.<UUID>getArgument(0))));
        given(orders.markPaid(any(), any())).willAnswer(call -> {
            if (markPaidResult == MarkPaid.OK) {
                UUID id = call.getArgument(0);
                OrderInfo o = known.get(id);
                known.put(id, new OrderInfo(id, o.number(), o.buyerId(), o.sellerId(), "TO_SHIP", o.total()));
            }
            return markPaidResult;
        });
    }

    @Test
    void methodsAreListed() throws Exception {
        mvc.perform(get("/api/v1/payments/methods").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains("BLIK", "CARD", "TRANSFER")))
                .andExpect(jsonPath("$[0].name").value("BLIK"));
    }

    @Test
    void blikPaysSeveralOrdersAndRetriesRejectedCode() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, 36099);
        UUID b = order(buyer, 131499);
        String body = pay(buyer, "k1", a + "," + b, "BLIK")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_BLIK"))
                .andExpect(jsonPath("$.amount.amount").value(36099 + 131499))
                .andExpect(jsonPath("$.redirectUrl").doesNotExist())
                .andExpect(jsonPath("$.orderIds", contains(a.toString(), b.toString())))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        // Ten sam Idempotency-Key: ta sama płatność.
        pay(buyer, "k1", a + "," + b, "BLIK").andExpect(jsonPath("$.id").value(id));

        blik(buyer, id, "000000")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Bank odrzucił kod BLIK. Wygeneruj nowy kod i spróbuj ponownie."));
        blik(buyer, id, "12345").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Kod BLIK ma 6 cyfr."));
        blik(UUID.randomUUID(), id, "123456").andExpect(status().isNotFound());
        blik(buyer, id, "123456").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        verify(orders).markPaid(a, UUID.fromString(id));
        verify(orders).markPaid(b, UUID.fromString(id));
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().isOrdersConfirmed()).isTrue();

        blik(buyer, id, "123456").andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Ta płatność jest już opłacona."));
        pay(buyer, "k2", a.toString(), "BLIK").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Zamówienie #" + a.toString().substring(0, 4) + " nie czeka już na płatność."));
        mvc.perform(get("/api/v1/payments/" + id).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void threeRejectedCodesFailThePayment() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, 9900);
        String id = id(pay(buyer, "k1", a.toString(), "BLIK"));
        blik(buyer, id, "000000").andExpect(status().isUnprocessableContent());
        blik(buyer, id, "000000").andExpect(status().isUnprocessableContent());
        blik(buyer, id, "000000").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Bank odrzucił kod BLIK 3 razy. Zacznij płatność od nowa."));
        mvc.perform(get("/api/v1/payments/" + id).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("Bank odrzucił kod BLIK 3 razy."));
        blik(buyer, id, "123456").andExpect(status().isConflict());
        // Nowa płatność za to samo zamówienie jest możliwa.
        pay(buyer, "k2", a.toString(), "BLIK").andExpect(status().isCreated());
        verify(orders, never()).markPaid(any(), any());
    }

    @Test
    void validation() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID mine = order(buyer, 9900);
        UUID other = order(UUID.randomUUID(), 9900);
        pay(buyer, "k1", other.toString(), "BLIK").andExpect(status().isNotFound());
        pay(buyer, "k2", UUID.randomUUID().toString(), "BLIK").andExpect(status().isNotFound());
        pay(buyer, "k3", "nie-uuid", "BLIK").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).header("Idempotency-Key", "k4")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"referenceType\":\"BOOKING\",\"referenceId\":\"" + mine + "\",\"method\":\"BLIK\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Nie znaleźliśmy tej rezerwacji."));
        mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(mine.toString(), "BLIK")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Brak nagłówka Idempotency-Key (do 100 znaków)."));
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body(mine.toString(), "BLIK")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cardGoesThroughSandboxPageAndSignedWebhook() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, 24900);
        String first = id(pay(buyer, "k1", a.toString(), "CARD").andExpect(jsonPath("$.status").value("PENDING")));
        mvc.perform(get("/api/v1/payments/" + first).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.redirectUrl").value("http://localhost:8080/api/v1/payments/sandbox/" + first));

        String failed = "{\"paymentId\":\"" + first + "\",\"result\":\"FAILED\"}";
        webhook(failed, "zly-podpis").andExpect(status().isUnauthorized());
        webhook(failed, signature.sign(failed)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/payments/" + first).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.redirectUrl").doesNotExist());
        // Ponowiony webhook z innym wynikiem nie zmienia rozliczonej płatności.
        String late = "{\"paymentId\":\"" + first + "\",\"result\":\"SUCCEEDED\"}";
        webhook(late, signature.sign(late)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/payments/" + first).header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(jsonPath("$.status").value("FAILED"));

        String second = id(pay(buyer, "k2", a.toString(), "TRANSFER"));
        mvc.perform(get("/api/v1/payments/sandbox/" + second))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zapłać 249,00 zł")))
                .andExpect(content().string(containsString("Przelew online")))
                .andExpect(content().string(containsString("<meta name=\"referrer\" content=\"same-origin\">")));
        mvc.perform(post("/api/v1/payments/sandbox/" + second).param("result", "success"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost:4200/zamowienie/potwierdzenie?payment=" + second + "&orders=" + a));
        mvc.perform(get("/api/v1/payments/" + second).header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(get("/api/v1/payments/sandbox/" + second)).andExpect(content().string(containsString("już rozliczona")));
    }

    @Test
    void newPaymentReplacesUnfinishedOne() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, 9900);
        String first = id(pay(buyer, "k1", a.toString(), "BLIK"));
        id(pay(buyer, "k2", a.toString(), "CARD"));
        blik(buyer, first, "123456").andExpect(status().isConflict());
        mvc.perform(get("/api/v1/payments/" + first).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("Zastąpiona nową płatnością."));
    }

    @Test
    void expiredOrderIsNotCharged() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, 9900);
        String id = id(pay(buyer, "k1", a.toString(), "BLIK"));
        OrderInfo o = known.get(a);
        known.put(a, new OrderInfo(a, o.number(), buyer, o.sellerId(), "CANCELLED", o.total()));
        blik(buyer, id, "123456")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reason").value(containsString("zostało anulowane albo wygasło. Nie pobraliśmy pieniędzy.")));
        verify(orders, never()).markPaid(any(), any());
    }

    @Test
    void ordersAreConfirmedLaterWhenOrderServiceIsDown() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID a = order(buyer, 9900);
        String id = id(pay(buyer, "k1", a.toString(), "BLIK"));
        markPaidResult = MarkPaid.UNAVAILABLE;
        blik(buyer, id, "123456").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().isOrdersConfirmed()).isFalse();

        markPaidResult = MarkPaid.OK;
        service.confirmPending();
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().isOrdersConfirmed()).isTrue();
        assertThat(known.get(a).status()).isEqualTo("TO_SHIP");
    }

    private UUID order(UUID buyer, long total) {
        UUID id = UUID.randomUUID();
        known.put(id, new OrderInfo(id, "#" + id.toString().substring(0, 4), buyer, UUID.randomUUID(), "AWAITING_PAYMENT", total));
        return id;
    }

    private ResultActions pay(UUID buyer, String key, String referenceIds, String method) throws Exception {
        return mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body(referenceIds, method)));
    }

    private ResultActions blik(UUID buyer, String paymentId, String code) throws Exception {
        return mvc.perform(post("/api/v1/payments/" + paymentId + "/blik").header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions webhook(String payload, String sig) throws Exception {
        return mvc.perform(post("/api/v1/payments/webhooks/sandbox").header(WebhookSignature.HEADER, sig)
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String body(String referenceIds, String method) {
        return "{\"referenceType\":\"ORDER\",\"referenceId\":\"" + referenceIds + "\",\"method\":\"" + method + "\"}";
    }

    private static String bearer(UUID userId) {
        return "Bearer user-" + userId;
    }
}
