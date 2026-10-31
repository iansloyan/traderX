package finos.traderx.cdmservice.master;

/**
 * A CDM master (security, party) that serves projections only once it is completely seeded.
 * {@code /health} reports "ok" only when every master is ready (Ruling R18).
 */
public interface CdmMaster {

  boolean isReady();
}
