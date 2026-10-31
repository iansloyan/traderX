package finos.traderx.cdmservice.party;

/**
 * account-service's {@code AccountUser} shape ({@code accountId}, {@code username}) plus the
 * {@code cdmRef} of the related person ({@code cdm:person:<username>}).
 */
public record AccountUserProjection(int accountId, String username, String cdmRef) {
}
