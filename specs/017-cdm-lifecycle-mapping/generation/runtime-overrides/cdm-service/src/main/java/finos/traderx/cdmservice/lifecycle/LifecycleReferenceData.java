package finos.traderx.cdmservice.lifecycle;

import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.Party;
import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.security.SecurityMaster;
import java.util.Optional;

/**
 * The reference data the lifecycle mappers read: securities and parties, which CDM masters own.
 * The mappers only read it, so they stay pure functions of their inputs and this data.
 */
public interface LifecycleReferenceData {

  /** The security with this TraderX ticker. */
  Optional<Security> security(String ticker);

  /** The account party (as stored: it may embed identifier-only persons). */
  Optional<Party> account(int accountId);

  /** The execution venue party (the TraderX order matcher). */
  Party venue();

  /** Reference data served by the security and party masters. */
  static LifecycleReferenceData of(SecurityMaster securities, PartyMaster parties) {
    return new LifecycleReferenceData() {
      @Override
      public Optional<Security> security(String ticker) {
        return securities.findByTicker(ticker);
      }

      @Override
      public Optional<Party> account(int accountId) {
        return parties.findParty(accountId);
      }

      @Override
      public Party venue() {
        return parties.venue();
      }
    };
  }
}
