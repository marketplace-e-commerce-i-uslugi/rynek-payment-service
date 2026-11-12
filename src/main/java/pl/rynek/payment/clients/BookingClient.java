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
import pl.rynek.payment.clients.OrderClient.MarkPaid;

/**
 * Rezerwacje z booking-service: zaliczka do opłacenia, oznaczenie wpłaty i statusy do salda usługodawcy (zaliczka jest
 * dostępna do wypłaty po wykonaniu usługi).
 */
@Component
public class BookingClient {

    static final int BATCH = 100;

    private final RestClient http;

    public BookingClient(@Value("${rynek.booking.url}") String bookingUrl) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(bookingUrl).requestFactory(requests).build();
    }

    public Optional<BookingInfo> booking(UUID bookingId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/bookings/{id}", bookingId).retrieve().body(BookingInfo.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw unavailable();
        }
    }

    /** Wiele rezerwacji naraz (po {@value #BATCH}); nieistniejące są pomijane. */
    public List<BookingInfo> bookings(List<UUID> bookingIds) {
        List<BookingInfo> found = new ArrayList<>();
        for (int i = 0; i < bookingIds.size(); i += BATCH) {
            List<UUID> chunk = bookingIds.subList(i, Math.min(i + BATCH, bookingIds.size()));
            try {
                BookingInfo[] page = http.get().uri(u -> u.path("/internal/bookings").queryParam("ids", chunk.toArray()).build())
                        .retrieve().body(BookingInfo[].class);
                if (page != null) {
                    found.addAll(List.of(page));
                }
            } catch (RestClientResponseException | ResourceAccessException e) {
                throw unavailable();
            }
        }
        return found;
    }

    /** {@code REJECTED}: rezerwacja anulowana albo nieistniejąca (409/404); {@code UNAVAILABLE}: spróbować później. */
    public MarkPaid markPaid(UUID bookingId, UUID paymentId) {
        try {
            http.post().uri("/internal/bookings/{id}/mark-paid", bookingId).body(Map.of("paymentId", paymentId)).retrieve().toBodilessEntity();
            return MarkPaid.OK;
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
            return MarkPaid.REJECTED;
        } catch (RestClientResponseException | ResourceAccessException e) {
            return MarkPaid.UNAVAILABLE;
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Serwis rezerwacji jest niedostępny. Spróbuj ponownie za chwilę.");
    }

    /** {@code status} to nazwa stałej z booking-service, np. {@code AWAITING_DEPOSIT}; {@code providerId} odbiera zaliczkę. */
    public record BookingInfo(UUID id, String title, UUID customerId, UUID providerId, String status, long deposit) {

        public boolean awaitingDeposit() {
            return "AWAITING_DEPOSIT".equals(status);
        }

        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }
}
