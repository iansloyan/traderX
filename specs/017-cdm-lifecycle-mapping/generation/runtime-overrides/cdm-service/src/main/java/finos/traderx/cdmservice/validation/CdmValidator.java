package finos.traderx.cdmservice.validation;

import cdm.event.common.BusinessEvent;
import cdm.event.qualification.functions.Qualify_Execution;
import com.google.inject.Injector;
import com.regnosys.rosetta.common.validation.RosettaTypeValidator;
import com.regnosys.rosetta.common.validation.ValidationReport;
import com.rosetta.model.lib.RosettaModelObject;
import com.rosetta.model.lib.validation.ValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Validates CDM objects with the Rune runtime, to the bar set for state 017: cdm-java
 * type/cardinality validation plus data-rule conditions.
 *
 * <p>{@link #validate} runs rune-common's {@link RosettaTypeValidator} (obtained from
 * {@code cdmInjector}, so its {@code ValidatorFactory} is the one {@code CdmRuntimeModule}
 * binds). It walks the whole object tree and, for every model object in it, applies the
 * generated cardinality validator ({@code *Validator}), type-format validator
 * ({@code *TypeFormatValidator}) and every data-rule condition of the type.
 *
 * <p>{@link #validateExecution} also evaluates the CDM qualification function
 * {@link Qualify_Execution}; an event it does not qualify is {@code NOT_QUALIFIED}, whatever
 * its validation result.
 *
 * <p>Validation never throws: a failure inside the Rune runtime is reported as an
 * {@code INVALID} result, because validation status is data about the object, never a reason
 * to fail a TraderX request.
 */
@Component
public class CdmValidator {

  /** The first message of a {@code NOT_QUALIFIED} result. */
  public static final String NOT_QUALIFIED_MESSAGE = "Qualify_Execution=false";

  /**
   * The only message of the result every master object was stored with before this validator
   * existed (the placeholder seam of Tasks 4 and 5, which validated nothing). Such rows are
   * re-validated at seed time (Ruling R16), see {@link #isPlaceholder}.
   */
  public static final String LEGACY_PLACEHOLDER_MESSAGE =
      "not validated: placeholder until CdmValidator (Task 8)";

  private static final Logger log = LoggerFactory.getLogger(CdmValidator.class);

  private final RosettaTypeValidator typeValidator;
  private final Qualify_Execution qualifyExecution;

  public CdmValidator(@Qualifier("cdmInjector") Injector cdmInjector) {
    this.typeValidator = cdmInjector.getInstance(RosettaTypeValidator.class);
    this.qualifyExecution = cdmInjector.getInstance(Qualify_Execution.class);
  }

  /**
   * Type/cardinality and condition validation of {@code object} and everything it contains.
   *
   * @return {@code VALID} with no messages, or {@code INVALID} with one message per failure,
   *     formatted {@code <path>: <validation type> <rule>: <reason>}
   */
  public CdmValidationResult validate(RosettaModelObject object) {
    if (object == null) {
      return new CdmValidationResult(Status.INVALID, List.of("no CDM object to validate"));
    }
    try {
      List<String> failures = failures(object);
      return failures.isEmpty()
          ? new CdmValidationResult(Status.VALID, List.of())
          : new CdmValidationResult(Status.INVALID, failures);
    } catch (RuntimeException e) {
      log.warn("CDM validation of {} failed with an exception", object.getType().getName(), e);
      return new CdmValidationResult(Status.INVALID,
          List.of("validation error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }
  }

  /**
   * {@link #validate} plus {@link Qualify_Execution}. If the event does not qualify as an
   * execution the result is {@code NOT_QUALIFIED}, with {@value #NOT_QUALIFIED_MESSAGE} first
   * and any validation failures after it; otherwise it is the validation result.
   */
  public CdmValidationResult validateExecution(BusinessEvent event) {
    CdmValidationResult validation = validate(event);
    if (event == null) {
      return validation;
    }
    boolean qualified;
    try {
      qualified = Boolean.TRUE.equals(qualifyExecution.evaluate(event));
    } catch (RuntimeException e) {
      log.warn("Qualify_Execution failed with an exception", e);
      List<String> messages = new ArrayList<>(validation.messages());
      messages.add("qualification error: " + e.getClass().getSimpleName() + ": "
          + e.getMessage());
      return new CdmValidationResult(Status.INVALID, messages);
    }
    if (qualified) {
      return validation;
    }
    List<String> messages = new ArrayList<>();
    messages.add(NOT_QUALIFIED_MESSAGE);
    messages.addAll(validation.messages());
    return new CdmValidationResult(Status.NOT_QUALIFIED, messages);
  }

  /** True for a result stored by the placeholder validator, i.e. never really validated. */
  public static boolean isPlaceholder(CdmValidationResult result) {
    return result != null && result.messages().contains(LEGACY_PLACEHOLDER_MESSAGE);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private List<String> failures(RosettaModelObject object) {
    ValidationReport report =
        typeValidator.runProcessStep((Class) object.getType(), object);
    return report.validationFailures().stream()
        .map(CdmValidator::describe)
        .distinct()
        .toList();
  }

  private static String describe(ValidationResult<?> failure) {
    String path = failure.getPath() == null ? failure.getModelObjectName()
        : failure.getPath().buildPath();
    return path + ": " + failure.getValidationType() + " " + failure.getName() + ": "
        + failure.getFailureReason().orElse("failed");
  }
}
