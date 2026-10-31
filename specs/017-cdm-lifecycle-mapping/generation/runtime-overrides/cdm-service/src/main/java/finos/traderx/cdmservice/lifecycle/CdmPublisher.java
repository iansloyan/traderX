package finos.traderx.cdmservice.lifecycle;

/**
 * Publishes stored CDM objects for other services. The payload is the JSON
 * {@code {cdmRef, globalKey, cdmType, validation, object}}, with {@code object} the CDM object
 * in its Rosetta JSON form.
 */
@FunctionalInterface
public interface CdmPublisher {

  /**
   * Every stored {@code WorkflowStep}: order proposal versions (submitted, approved, cancel),
   * fill and direct-trade proposals and acceptances, rejections.
   */
  String WORKFLOW = "cdm.workflow";
  /**
   * The {@code BusinessEvent} of each newly stored accepted step, taken from the step (not stored
   * on its own); the message also carries {@code stepGlobalKey}, the step's store global key.
   */
  String EVENTS = "cdm.events";
  /** Stored {@code TradeState}s (one per trade, under {@code cdm:trade:<tradeId>}). */
  String TRADE_STATE = "cdm.tradestate";

  /** Publishes nothing (the NATS listener is disabled). */
  CdmPublisher NONE = (subject, payload) -> {
  };

  void publish(String subject, byte[] payload);
}
