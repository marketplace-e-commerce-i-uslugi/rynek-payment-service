package pl.rynek.payment.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.payment.api.dto.CursorPage;

/** Paginacja kursorowa: kursor to zakodowane przesunięcie; pobieramy o jeden element więcej, żeby wiedzieć o następnej stronie. */
final class Pages {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private Pages() {
    }

    @FunctionalInterface
    interface Fetch<T> {
        List<T> fetch(int offset, int limit);
    }

    static <T, R> CursorPage<R> page(String cursor, Integer limit, Fetch<T> fetch, Function<T, R> view) {
        int offset = offset(cursor);
        int size = limit(limit);
        List<T> rows = fetch.fetch(offset, size + 1);
        boolean more = rows.size() > size;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + (offset + size)).getBytes(StandardCharsets.UTF_8)) : null;
        return new CursorPage<>((more ? rows.subList(0, size) : rows).stream().map(view).toList(), next);
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int offset = decoded.startsWith("o:") ? Integer.parseInt(decoded.substring(2)) : -1;
            if (offset < 0) {
                throw new IllegalArgumentException();
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy kursor. Zacznij od pierwszej strony.");
        }
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit musi wynosić od 1 do " + MAX_LIMIT + ".");
        }
        return limit;
    }
}
