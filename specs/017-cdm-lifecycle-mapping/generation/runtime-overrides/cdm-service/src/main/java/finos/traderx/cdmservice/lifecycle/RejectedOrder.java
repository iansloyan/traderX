package finos.traderx.cdmservice.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order create request the matcher rejected, as published on {@code /orders/rejected}. The
 * request fields echo the request as received and may each be {@code null}; {@code rejectionId}
 * ({@code rej-<uuid>}) and {@code rejectedAt} are always set by the matcher.
 */
public record RejectedOrder(String rejectionId, Integer accountId, String security, String side,
    Integer quantity, BigDecimal limitPrice, String reason, Instant rejectedAt) {
}
