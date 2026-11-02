package finos.traderx.cdmservice.party;

/**
 * account-service's {@code Account} shape ({@code id}, {@code displayName}) plus the
 * {@code cdmRef} of the CDM Party ({@code cdm:party:<id>}) and the TraderX subsidiary that
 * services the account and trades with it as dealer (the party's CDM
 * {@code account.servicingParty}): its {@code cdmRef} and name, null if it has none.
 */
public record AccountProjection(int id, String displayName, String cdmRef,
    String servicingPartyRef, String servicingPartyName) {
}
