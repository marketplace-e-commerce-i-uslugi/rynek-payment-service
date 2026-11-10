package pl.rynek.payment.api.dto;

import java.util.UUID;

/** Treść webhooka dostawcy (symulator): {@code {"paymentId": "...", "result": "SUCCEEDED" | "FAILED"}}. */
public record WebhookEvent(UUID paymentId, String result) {
}
