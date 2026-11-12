package pl.rynek.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import pl.rynek.payment.clients.BookingClient.BookingInfo;
import pl.rynek.payment.clients.OrderClient;
import pl.rynek.payment.clients.OrderClient.MarkPaid;
import pl.rynek.payment.payments.SellerEarningRepository;

/** Zaliczki za rezerwacje: płatność, oznaczenie wpłaty w booking-service, saldo usługodawcy i zwrot po anulowaniu. */
@SpringBootTest(properties = {"rynek.payments.confirm-retry=1h", "rynek.payments.earnings-backfill=1h", "rynek.payments.refund-notify-retry=1h",
        "rynek.payouts.cron=-"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BookingDepositTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    SellerEarningRepository earnings;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    OrderClient orders;

    @MockitoBean
    BookingClient bookings;

    final Map<UUID, BookingInfo> known = new ConcurrentHashMap<>();

    @BeforeEach
    void stubs() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256")
                .subject(call.<String>getArgument(0).substring(5)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
        given(bookings.booking(any())).willAnswer(call -> Optional.ofNullable(known.get(call.<UUID>getArgument(0))));
        given(bookings.bookings(any())).willAnswer(call -> call.<List<UUID>>getArgument(0).stream().map(known::get).filter(b -> b != null).toList());
        given(bookings.markPaid(any(), any())).willAnswer(call -> {
            setStatus(call.getArgument(0), "REQUESTED");
            return MarkPaid.OK;
        });
    }

    @Test
    void depositIsPaidReleasedAfterServiceAndConfirmedInBookingService() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        UUID booking = booking(customer, provider, "AWAITING_DEPOSIT", 5000);
        String id = JsonPath.read(pay(customer, booking.toString(), "BLIK").andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount.amount").value(5000))
                .andExpect(jsonPath("$.orderIds[0]").value(booking.toString()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        blik(customer, id).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        verify(bookings).markPaid(booking, UUID.fromString(id));
        verify(orders, never()).markPaid(any(), any());
        assertThat(earnings.findById(booking)).hasValueSatisfying(e -> assertThat(e.getSellerId()).isEqualTo(provider));

        balance(provider).andExpect(jsonPath("$.pending.amount").value(5000)).andExpect(jsonPath("$.available.amount").value(0));
        setStatus(booking, "CONFIRMED");
        balance(provider).andExpect(jsonPath("$.pending.amount").value(5000));
        setStatus(booking, "COMPLETED");
        balance(provider).andExpect(jsonPath("$.pending.amount").value(0)).andExpect(jsonPath("$.available.amount").value(5000));
    }

    @Test
    void cancelledBookingGetsDepositBackAndOpenPaymentFails() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        UUID paid = booking(customer, provider, "AWAITING_DEPOSIT", 3000);
        String payment = JsonPath.read(pay(customer, paid.toString(), "BLIK").andReturn().getResponse().getContentAsString(), "$.id");
        blik(customer, payment).andExpect(status().isOk());
        setStatus(paid, "CANCELLED");
        mvc.perform(post("/internal/bookings/" + paid + "/cancelled")).andExpect(status().isNoContent());
        mvc.perform(post("/internal/bookings/" + paid + "/cancelled")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/payments/" + payment).header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(jsonPath("$.status").value("REFUNDED")).andExpect(jsonPath("$.refunded.amount").value(3000));
        balance(provider).andExpect(jsonPath("$.pending.amount").value(0));

        // Wygasłe przetrzymanie: płatność kartą w toku jest nieudana, a strona symulatora wraca na potwierdzenie rezerwacji.
        UUID expired = booking(customer, provider, "AWAITING_DEPOSIT", 3000);
        String card = JsonPath.read(pay(customer, expired.toString(), "CARD").andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/payments/sandbox/" + card).param("result", "failure"))
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost:4200/zamowienie/potwierdzenie?payment=" + card + "&booking=" + expired));
        String second = JsonPath.read(pay(customer, expired.toString(), "CARD").andReturn().getResponse().getContentAsString(), "$.id");
        setStatus(expired, "CANCELLED");
        mvc.perform(post("/internal/bookings/" + expired + "/cancelled")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/payments/" + second).header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("Rezerwacja została anulowana albo wygasła. Nie pobraliśmy pieniędzy."));
    }

    @Test
    void depositValidation() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        pay(UUID.randomUUID(), booking(customer, provider, "AWAITING_DEPOSIT", 3000).toString(), "BLIK").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Nie znaleźliśmy tej rezerwacji."));
        pay(customer, booking(customer, provider, "REQUESTED", 3000).toString(), "BLIK").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Rezerwacja nie czeka już na zaliczkę."));
        pay(customer, booking(customer, provider, "AWAITING_DEPOSIT", 0).toString(), "BLIK").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Ta rezerwacja nie wymaga zaliczki."));
        pay(customer, booking(customer, provider, "AWAITING_DEPOSIT", 3000) + "," + booking(customer, provider, "AWAITING_DEPOSIT", 3000), "BLIK")
                .andExpect(status().isBadRequest());
        // Rezerwacja anulowana w trakcie płatności: pieniądze nie są pobierane.
        UUID late = booking(customer, provider, "AWAITING_DEPOSIT", 3000);
        String id = JsonPath.read(pay(customer, late.toString(), "BLIK").andReturn().getResponse().getContentAsString(), "$.id");
        setStatus(late, "CANCELLED");
        blik(customer, id).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(earnings.findById(late)).isEmpty();
    }

    private UUID booking(UUID customer, UUID provider, String status, long deposit) {
        UUID id = UUID.randomUUID();
        known.put(id, new BookingInfo(id, "Malowanie ścian", customer, provider, status, deposit));
        return id;
    }

    private void setStatus(UUID id, String status) {
        BookingInfo b = known.get(id);
        known.put(id, new BookingInfo(id, b.title(), b.customerId(), b.providerId(), status, b.deposit()));
    }

    private ResultActions pay(UUID customer, String bookingIds, String method) throws Exception {
        return mvc.perform(post("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(customer)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"referenceType\":\"BOOKING\",\"referenceId\":\"" + bookingIds + "\",\"method\":\"" + method + "\"}"));
    }

    private ResultActions blik(UUID customer, String paymentId) throws Exception {
        return mvc.perform(post("/api/v1/payments/" + paymentId + "/blik").header(HttpHeaders.AUTHORIZATION, bearer(customer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"));
    }

    private ResultActions balance(UUID seller) throws Exception {
        return mvc.perform(get("/api/v1/seller/balance").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());
    }

    private static String bearer(UUID userId) {
        return "Bearer user-" + userId;
    }
}
