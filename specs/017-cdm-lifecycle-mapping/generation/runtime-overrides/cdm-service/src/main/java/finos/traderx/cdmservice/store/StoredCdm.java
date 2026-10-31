package finos.traderx.cdmservice.store;

import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import java.time.Instant;

/**
 * One stored version of a CDM object.
 *
 * @param globalKey         the object's meta globalKey, or the SHA-256 of its canonical JSON
 * @param ref               the TraderX-derived reference the object is stored under
 * @param cdmType           fully qualified CDM interface name, e.g. {@code cdm.base.staticdata.party.Party}
 * @param json              the object serialised with {@code cdmObjectMapper} (Rosetta JSON)
 * @param previousGlobalKey the version this one supersedes, or {@code null} for the first
 * @param validation        validation outcome recorded at write time
 * @param createdAt         write time (microsecond precision)
 * @param producedBy        the object under another ref that produced this one (a trade state's
 *                          accepted workflow step), or {@code null}
 */
public record StoredCdm(
    String globalKey,
    CdmRef ref,
    String cdmType,
    String json,
    String previousGlobalKey,
    CdmValidationResult validation,
    Instant createdAt,
    ProducedBy producedBy) {

  /** A version with no producer under another ref. */
  public StoredCdm(String globalKey, CdmRef ref, String cdmType, String json,
      String previousGlobalKey, CdmValidationResult validation, Instant createdAt) {
    this(globalKey, ref, cdmType, json, previousGlobalKey, validation, createdAt, null);
  }
}
