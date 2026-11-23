package pl.rynek.payment.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Prowizja platformy: 10% od wartości towarów (bez dostawy), saldo i lista przychodów pokazują kwotę netto. */
@SpringBootTest(properties = {"rynek.payments.commission-percent=10", "rynek.payments.confirm-retry=1h", "rynek.payments.earnings-backfill=1h",
        "rynek.payments.refund-notify-retry=1h", "rynek.payouts.cron=-"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CommissionTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    OrderClient orders;

    @MockitoBean
    BookingClient bookingClient;

    final Map<UUID, OrderInfo> known = new ConcurrentHashMap<>();

    @BeforeEach
    void stubs() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> {
            String token = call.getArgument(0);
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token.substring(token.indexOf('-') + 1))
                    .claim("roles", List.of("BUYER")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        });
        given(orders.order(any())).willAnswer(call -> Optional.ofNullable(known.get(call.<UUID>getArgument(0))));
        given(orders.orders(any())).willAnswer(call -> call.<List<UUID>>getArgument(0).stream().map(known::get).filter(o -> o != null).toList());
        given(orders.markPaid(any(), any())).willAnswer(call -> {
            UUID id = call.getArgument(0);
            OrderInfo o = known.get(id);
            known.put(id, new OrderInfo(id, o.number(), o.buyerId(), o.sellerId(), "TO_SHIP", o.total(), o.itemsTotal()));
            return MarkPaid.OK;
        });
    }

    @Test
    void commissionIsTakenFromItemsOnlyAndShownInBalanceAndEarnings() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        // Towary 100,00 zł + dostawa 12,99 zł = 112,99 zł; prowizja 10% od towarów to 10,00 zł.
        UUID id = UUID.randomUUID();
        known.put(id, new OrderInfo(id, "#1", buyer, seller, "AWAITING_PAYMENT", 11299, 10000));
        String payment = JsonPath.read(mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + buyer)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"referenceType\":\"ORDER\",\"referenceId\":\"" + id + "\",\"method\":\"BLIK\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/payments/" + payment + "/blik").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}")).andExpect(status().isOk());

        balance(seller).andExpect(jsonPath("$.pending.amount").value(11299 - 1000)).andExpect(jsonPath("$.available.amount").value(0))
                .andExpect(jsonPath("$.commissionPercent").value(10.0));
        mvc.perform(get("/api/v1/seller/earnings").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].gross.amount").value(11299))
                .andExpect(jsonPath("$.items[0].commission.amount").value(1000))
                .andExpect(jsonPath("$.items[0].net.amount").value(10299))
                .andExpect(jsonPath("$.items[0].released").value(false))
                .andExpect(jsonPath("$.items[0].label").value("Zamówienie #1"))
                .andExpect(jsonPath("$.items[0].refunded").value(false));

        // Realizacja zamówienia odblokowuje kwotę netto.
        known.put(id, new OrderInfo(id, "#1", buyer, seller, "COMPLETED", 11299, 10000));
        balance(seller).andExpect(jsonPath("$.available.amount").value(10299)).andExpect(jsonPath("$.pending.amount").value(0));

        // Zwrot częściowy zmniejsza prowizję proporcjonalnie do zwróconej części: 1000 * 6299 / 11299 = 557.
        given(orders.refunded(any(), anyBoolean())).willReturn(true);
        mvc.perform(post("/api/v1/payments/" + payment + "/refund").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + seller)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":{\"amount\":5000,\"currency\":\"PLN\"}}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/seller/earnings").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + seller))
                .andExpect(jsonPath("$.items[0].gross.amount").value(6299))
                .andExpect(jsonPath("$.items[0].commission.amount").value(557))
                .andExpect(jsonPath("$.items[0].net.amount").value(5742));
        balance(seller).andExpect(jsonPath("$.available.amount").value(5742));
    }

    private ResultActions balance(UUID seller) throws Exception {
        return mvc.perform(get("/api/v1/seller/balance").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + seller)).andExpect(status().isOk());
    }
}
