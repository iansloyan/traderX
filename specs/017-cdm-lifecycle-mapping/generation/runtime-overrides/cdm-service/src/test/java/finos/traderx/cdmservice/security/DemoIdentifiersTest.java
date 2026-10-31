package finos.traderx.cdmservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DemoIdentifiersTest {

  /** Demo FIGI: BBG + 8 chars from digits and consonants (no vowels) + a check digit. */
  private static final String FIGI_PATTERN = "^BBG[0-9B-DF-HJ-NP-TV-Z]{8}[0-9]$";

  @Test
  void isinHasXxPrefixAndValidCheckDigit() {
    String isin = DemoIdentifiers.isin("IBM");

    assertThat(isin).matches("^XX[0-9A-Z]{9}[0-9]$");
    assertThat(isin.charAt(11) - '0').isEqualTo(DemoIdentifiers.isinCheckDigit(isin.substring(0, 11)));
  }

  @Test
  void figiIsDeterministic() {
    String first = DemoIdentifiers.figi("IBM");
    String second = DemoIdentifiers.figi("IBM");

    assertThat(first).isEqualTo(second);
    assertThat(first).matches(FIGI_PATTERN);
    assertThat(first.charAt(11) - '0').isEqualTo(DemoIdentifiers.figiCheckDigit(first.substring(0, 11)));
  }

  @Test
  void isinIsDeterministicAndDiffersPerTicker() {
    assertThat(DemoIdentifiers.isin("AAPL")).isEqualTo(DemoIdentifiers.isin("AAPL"));
    assertThat(DemoIdentifiers.isin("AAPL")).isNotEqualTo(DemoIdentifiers.isin("IBM"));
    assertThat(DemoIdentifiers.figi("AAPL")).isNotEqualTo(DemoIdentifiers.figi("IBM"));
  }

  @Test
  void isinCheckDigitMatchesRealIsins() {
    // IBM US4592001014, Apple US0378331005, BAE Systems GB0002634946.
    assertThat(DemoIdentifiers.isinCheckDigit("US459200101")).isEqualTo(4);
    assertThat(DemoIdentifiers.isinCheckDigit("US037833100")).isEqualTo(5);
    assertThat(DemoIdentifiers.isinCheckDigit("GB000263494")).isEqualTo(6);
  }

  @Test
  void figiCheckDigitMatchesRealFigis() {
    // IBM BBG000BLNNH6, Apple BBG000B9XRY4.
    assertThat(DemoIdentifiers.figiCheckDigit("BBG000BLNNH")).isEqualTo(6);
    assertThat(DemoIdentifiers.figiCheckDigit("BBG000B9XRY")).isEqualTo(4);
  }

  @Test
  void demoValuesAreStableAcrossReleases() {
    // Pinned so a change to the scheme is a visible, deliberate decision: the values are
    // persisted in CDM Security masters and change their global keys.
    assertThat(DemoIdentifiers.isin("IBM")).isEqualTo(PinnedValues.IBM_ISIN);
    assertThat(DemoIdentifiers.figi("IBM")).isEqualTo(PinnedValues.IBM_FIGI);
  }

  @Test
  void rejectsBlankTicker() {
    assertThatThrownBy(() -> DemoIdentifiers.isin(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DemoIdentifiers.figi(null)).isInstanceOf(IllegalArgumentException.class);
  }

  /** Cross-checked with an independent Python implementation; see research.md. */
  private static final class PinnedValues {
    static final String IBM_ISIN = "XXIMQIJLGP45";
    static final String IBM_FIGI = "BBG821ZC5JC3";
  }
}
