package finos.traderx.cdmservice.master;

import com.rosetta.model.lib.RosettaModelObject;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ruling R16: master objects stored before the real {@link CdmValidator} existed carry the
 * placeholder result. When a master loads such a row at seed time it validates the object and
 * records the real result with {@link CdmStore#revalidate} (a new version of the same content,
 * written only when the result differs).
 *
 * <p>Validation status is data, not a gate: neither an {@code INVALID} result nor a failure to
 * record it stops the master becoming ready. A row whose re-validation could not be recorded
 * keeps the placeholder result and is re-validated on the next seed.
 */
public final class Revalidation {

  private static final Logger log = LoggerFactory.getLogger(Revalidation.class);

  private Revalidation() {
  }

  /** Re-validates {@code object} (the content of {@code stored}) if it has the placeholder result. */
  public static void ifPlaceholder(CdmStore store, CdmValidator validator, StoredCdm stored,
      RosettaModelObject object) {
    if (!CdmValidator.isPlaceholder(stored.validation())) {
      return;
    }
    try {
      CdmValidationResult result = validator.validate(object);
      store.revalidate(stored.ref(), object.getType(), stored.globalKey(), result);
      if (result.status() != CdmValidationResult.Status.VALID) {
        log.warn("Stored CDM {} re-validated as {}: {}", stored.ref(), result.status(),
            result.messages());
      }
    } catch (RuntimeException e) {
      log.warn("Could not record the re-validation of stored CDM {}; it keeps the placeholder "
          + "result until the next seed", stored.ref(), e);
    }
  }
}
