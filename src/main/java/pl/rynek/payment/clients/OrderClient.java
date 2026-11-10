package pl.rynek.payment.clients;

import java.time.Duration;
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

/** Zamówienia z order-service: kwota i status przed płatnością, oznaczenie jako opłacone po niej. */
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

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Serwis zamówień jest niedostępny. Spróbuj ponownie za chwilę.");
    }

    /** {@code status} to nazwa stałej z order-service, np. {@code AWAITING_PAYMENT}. */
    public record OrderInfo(UUID id, String number, UUID buyerId, UUID sellerId, String status, long total) {

        public boolean awaitingPayment() {
            return "AWAITING_PAYMENT".equals(status);
        }
    }
}
