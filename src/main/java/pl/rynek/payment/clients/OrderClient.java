package pl.rynek.payment.clients;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Zamówienia z order-service: kwota i status przed płatnością, oznaczenie jako opłacone po niej, statusy do salda
 * sprzedawcy (środki z zamówienia zrealizowanego są dostępne do wypłaty).
 */
@Component
public class OrderClient {

    private final RestClient http;

    public OrderClient(@Value("${rynek.order.url}") String orderUrl) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(orderUrl).requestFactory(requests).build();
    }

    public Optional<OrderInfo> order(UUID orderId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/orders/{id}", orderId).retrieve().body(OrderInfo.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw unavailable();
        }
    }

    /** Wiele zamówień naraz (po {@value #BATCH}); nieistniejące są pomijane. */
    public List<OrderInfo> orders(List<UUID> orderIds) {
        List<OrderInfo> found = new ArrayList<>();
        for (int i = 0; i < orderIds.size(); i += BATCH) {
            List<UUID> chunk = orderIds.subList(i, Math.min(i + BATCH, orderIds.size()));
            try {
                OrderInfo[] page = http.get().uri(u -> u.path("/internal/orders").queryParam("ids", chunk.toArray()).build())
                        .retrieve().body(OrderInfo[].class);
                if (page != null) {
                    found.addAll(List.of(page));
                }
            } catch (RestClientResponseException | ResourceAccessException e) {
                throw unavailable();
            }
        }
        return found;
    }

    static final int BATCH = 100;

    /** Wynik oznaczenia zamówienia jako opłaconego. */
    public enum MarkPaid { OK, REJECTED, UNAVAILABLE }

    /** {@code REJECTED}: zamówienie anulowane albo nieistniejące (409/404); {@code UNAVAILABLE}: spróbować później. */
    public MarkPaid markPaid(UUID orderId, UUID paymentId) {
        try {
            http.post().uri("/internal/orders/{id}/mark-paid", orderId).body(Map.of("paymentId", paymentId)).retrieve().toBodilessEntity();
            return MarkPaid.OK;
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
            return MarkPaid.REJECTED;
        } catch (RestClientResponseException | ResourceAccessException e) {
            return MarkPaid.UNAVAILABLE;
        }
    }

    /**
     * Zwrot pieniędzy za zamówienie: zgłoszony zwrot staje się przyjęty. {@code false}: order-service nie odpowiada,
     * trzeba ponowić; odrzucenie 4xx nie ma sensu ponawiać, więc też jest {@code true}.
     */
    public boolean refunded(UUID orderId) {
        try {
            http.post().uri("/internal/orders/{id}/refunded", orderId).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            return true;
        } catch (RestClientResponseException | ResourceAccessException e) {
            return false;
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Serwis zamówień jest niedostępny. Spróbuj ponownie za chwilę.");
    }

    /** {@code status} to nazwa stałej z order-service, np. {@code AWAITING_PAYMENT}. */
    public record OrderInfo(UUID id, String number, UUID buyerId, UUID sellerId, String status, long total) {

        public boolean awaitingPayment() {
            return "AWAITING_PAYMENT".equals(status);
        }

        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }
}
