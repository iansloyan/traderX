package finos.traderx.cdmservice.lifecycle;

import java.util.Locale;

/** The side of a TraderX order or trade, seen from the account. */
public enum Side {
  BUY,
  SELL;

  /** Parses TraderX's {@code Buy}/{@code Sell} (any case); {@code null} for null or blank. */
  public static Side parse(String side) {
    if (side == null || side.isBlank()) {
      return null;
    }
    return switch (side.trim().toUpperCase(Locale.ROOT)) {
      case "BUY" -> BUY;
      case "SELL" -> SELL;
      default -> throw new IllegalArgumentException("unknown side: " + side);
    };
  }
}
