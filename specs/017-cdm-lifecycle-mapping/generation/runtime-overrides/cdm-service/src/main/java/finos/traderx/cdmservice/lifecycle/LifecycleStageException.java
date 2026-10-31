package finos.traderx.cdmservice.lifecycle;

/** A lifecycle failure tagged with the stage it happened in (see {@link LifecycleFailures}). */
final class LifecycleStageException extends RuntimeException {

  private final String stage;

  LifecycleStageException(String stage, String message) {
    super(message);
    this.stage = stage;
  }

  LifecycleStageException(String stage, Throwable cause) {
    super(cause.getMessage(), cause);
    this.stage = stage;
  }

  String stage() {
    return stage;
  }
}
