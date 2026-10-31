package finos.traderx.cdmservice.party;

/**
 * A person as people-service serves it (its {@code Person} fields, camelCase), plus
 * {@code cdmRef} ({@code cdm:person:<logonId>}). Fields other than {@code logonId} and
 * {@code cdmRef} are null for an identifier-only person; such persons are never served by the
 * directory endpoints (Ruling R20).
 */
public record PersonProjection(String logonId, String fullName, String email, String employeeId,
    String department, String photoUrl, String cdmRef) {
}
