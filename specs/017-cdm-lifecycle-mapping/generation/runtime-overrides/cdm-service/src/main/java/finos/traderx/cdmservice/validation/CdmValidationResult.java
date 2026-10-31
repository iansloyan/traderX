package finos.traderx.cdmservice.validation;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of validating a CDM object (filled in by the validator, Task 8).
 *
 * @param status   overall result
 * @param messages human-readable failure details; empty when {@code VALID}
 */
public record CdmValidationResult(Status status, List<String> messages) {

  public enum Status {
    VALID,
    INVALID,
    NOT_QUALIFIED
  }

  public CdmValidationResult {
    Objects.requireNonNull(status, "status");
    messages = messages == null ? List.of() : List.copyOf(messages);
  }
}
