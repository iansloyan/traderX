package finos.traderx.cdmservice.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Synthetic, deterministic ISIN and FIGI values for seeded securities. They are demo data, not
 * real assignments (see research.md "Demo Identifier Scheme"):
 *
 * <ul>
 *   <li>ISIN: {@code XX} + 9 base-36 characters + ISIN check digit. {@code XX} is not an
 *       ISO 3166 country code, so the values cannot collide with real ISINs.</li>
 *   <li>FIGI: {@code BBG} + 8 characters from {@code 0-9} and the consonants {@code B-Z}
 *       (OpenFIGI never uses vowels) + FIGI check digit.</li>
 * </ul>
 *
 * <p>The body characters are the low-order digits, most significant first, of the SHA-256 of
 * {@code "isin:" + ticker} (or {@code "figi:" + ticker}) read as an unsigned big-endian integer,
 * written in the target alphabet. Changing this scheme changes persisted Security masters.
 */
public final class DemoIdentifiers {

  private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String FIGI_ALPHABET = "0123456789BCDFGHJKLMNPQRSTVWXYZ";

  private DemoIdentifiers() {
  }

  public static String isin(String ticker) {
    String body = "XX" + encode(hash("isin:", ticker), BASE36, 9);
    return body + isinCheckDigit(body);
  }

  public static String figi(String ticker) {
    String body = "BBG" + encode(hash("figi:", ticker), FIGI_ALPHABET, 8);
    return body + figiCheckDigit(body);
  }

  /**
   * ISO 6166 check digit for the first 11 characters of an ISIN: letters become their value
   * ({@code A}=10 ... {@code Z}=35) written as two digits, then the Luhn algorithm runs over the
   * resulting digit string, doubling every second digit starting from the rightmost.
   */
  static int isinCheckDigit(String first11) {
    requireLength(first11, 11);
    StringBuilder digits = new StringBuilder();
    for (char c : first11.toCharArray()) {
      digits.append(charValue(c));
    }
    int sum = 0;
    boolean doubleIt = true;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubleIt) {
        d *= 2;
      }
      sum += d / 10 + d % 10;
      doubleIt = !doubleIt;
    }
    return (10 - sum % 10) % 10;
  }

  /**
   * OpenFIGI check digit for the first 11 characters: each character's value ({@code 0-9},
   * {@code A}=10 ... {@code Z}=35) is doubled at every second position (2nd, 4th, ... from the
   * left), the decimal digits of all the values are summed, and the check digit is
   * {@code (10 - sum mod 10) mod 10}.
   */
  static int figiCheckDigit(String first11) {
    requireLength(first11, 11);
    int sum = 0;
    for (int i = 0; i < first11.length(); i++) {
      int value = charValue(first11.charAt(i));
      if (i % 2 == 1) {
        value *= 2;
      }
      sum += digitSum(value);
    }
    return (10 - sum % 10) % 10;
  }

  private static int charValue(char c) {
    int value = Character.digit(c, 36);
    if (value < 0 || Character.isLowerCase(c)) {
      throw new IllegalArgumentException("Not an identifier character: " + c);
    }
    return value;
  }

  private static int digitSum(int value) {
    int sum = 0;
    for (int v = value; v > 0; v /= 10) {
      sum += v % 10;
    }
    return sum;
  }

  private static BigInteger hash(String domain, String ticker) {
    if (ticker == null || ticker.isBlank()) {
      throw new IllegalArgumentException("ticker is required");
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((domain + ticker).getBytes(StandardCharsets.UTF_8));
      return new BigInteger(1, digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String encode(BigInteger value, String alphabet, int length) {
    BigInteger radix = BigInteger.valueOf(alphabet.length());
    char[] out = new char[length];
    BigInteger rest = value;
    for (int i = length - 1; i >= 0; i--) {
      BigInteger[] qr = rest.divideAndRemainder(radix);
      out[i] = alphabet.charAt(qr[1].intValue());
      rest = qr[0];
    }
    return new String(out);
  }

  private static void requireLength(String value, int length) {
    if (value == null || value.length() != length) {
      throw new IllegalArgumentException("Expected " + length + " characters: " + value);
    }
  }
}
