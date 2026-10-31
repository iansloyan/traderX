package finos.traderx.cdmservice.ref;

import java.util.Locale;

/**
 * Deterministic reference to a CDM object derived from a TraderX identifier, formatted
 * {@code cdm:<kind>:<externalKey>} (for example {@code cdm:party:22214} or
 * {@code cdm:position:22214:IBM}). The key may itself contain {@code ':'}.
 *
 * <p>{@link Kind#DIRECT} ({@code cdm:direct:<tradeId>}) is a cdm-service lookup address, not a
 * CDM concept: the workflow (proposed, accepted) of a trade booked from the trade ticket without
 * an order. The trade's {@code TradeState} stays under {@code cdm:trade:<tradeId>}.
 */
public record CdmRef(Kind kind, String key) {

  private static final String PREFIX = "cdm:";

  public enum Kind {
    PARTY, PERSON, SECURITY, ORDER, TRADE, POSITION, DIRECT;

    String token() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Kind fromToken(String token) {
      for (Kind kind : values()) {
        if (kind.token().equals(token)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown cdmRef kind: " + token);
    }
  }

  public CdmRef {
    if (kind == null) {
      throw new IllegalArgumentException("cdmRef kind is required");
    }
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("cdmRef key is required");
    }
  }

  public static CdmRef parse(String value) {
    if (value == null || !value.startsWith(PREFIX)) {
      throw new IllegalArgumentException("cdmRef must start with '" + PREFIX + "': " + value);
    }
    String rest = value.substring(PREFIX.length());
    int separator = rest.indexOf(':');
    if (separator < 0) {
      throw new IllegalArgumentException("cdmRef must be cdm:<kind>:<key>: " + value);
    }
    return new CdmRef(Kind.fromToken(rest.substring(0, separator)), rest.substring(separator + 1));
  }

  @Override
  public String toString() {
    return PREFIX + kind.token() + ":" + key;
  }
}
