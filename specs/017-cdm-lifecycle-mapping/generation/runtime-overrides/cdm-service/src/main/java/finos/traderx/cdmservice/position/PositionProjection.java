package finos.traderx.cdmservice.position;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A position in the shape 009 position-service serves ({@code accountId, security, quantity,
 * averageCostBasis, updated}) plus {@code cdmRef} ({@code cdm:position:<accountId>:<security>}),
 * derived from the CDM trade states. Served by {@code GET /projections/positions/{accountId}}.
 *
 * @param quantity         signed shares: bought minus sold
 * @param averageCostBasis computed as 009 trade-processor books it, trade by trade in execution
 *                         order (scale 3, HALF_UP; 0 when flat)
 * @param updated          execution time of the last trade in the security
 */
public record PositionProjection(Integer accountId, String security, Integer quantity,
    BigDecimal averageCostBasis, Instant updated, String cdmRef) {
}
