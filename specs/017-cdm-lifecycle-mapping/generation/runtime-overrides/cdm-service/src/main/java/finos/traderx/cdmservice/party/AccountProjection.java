package finos.traderx.cdmservice.party;

/**
 * account-service's {@code Account} shape ({@code id}, {@code displayName}) plus the
 * {@code cdmRef} of the CDM Party ({@code cdm:party:<id>}).
 */
public record AccountProjection(int id, String displayName, String cdmRef) {
}
