package finos.traderx.cdmservice.ref;

import static finos.traderx.cdmservice.ref.CdmRef.Kind.PARTY;
import static finos.traderx.cdmservice.ref.CdmRef.Kind.POSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CdmRefTest {

  @Test
  void roundTrips() {
    CdmRef ref = CdmRef.parse("cdm:position:22214:IBM");

    assertThat(ref).isEqualTo(new CdmRef(POSITION, "22214:IBM"));
    assertThat(ref.toString()).isEqualTo("cdm:position:22214:IBM");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "cdm:party:22214",
      "cdm:person:user01",
      "cdm:security:IBM",
      "cdm:order:ord-013-0001",
      "cdm:trade:7f3c2a",
      "cdm:direct:7f3c2a",
      "cdm:position:22214:IBM"
  })
  void roundTripsEveryKind(String value) {
    assertThat(CdmRef.parse(value).toString()).isEqualTo(value);
  }

  @Test
  void parsesDirect() {
    CdmRef ref = CdmRef.parse("cdm:direct:TRADE-22214-AABBCC");

    assertThat(ref.kind()).isEqualTo(CdmRef.Kind.DIRECT);
    assertThat(ref.key()).isEqualTo("TRADE-22214-AABBCC");
    assertThat(ref.toString()).isEqualTo("cdm:direct:TRADE-22214-AABBCC");
  }

  @Test
  void formatsKindInLowerCase() {
    assertThat(new CdmRef(PARTY, "22214").toString()).isEqualTo("cdm:party:22214");
  }

  @Test
  void rejectsUnknownKind() {
    assertThatThrownBy(() -> CdmRef.parse("cdm:foo:1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "party:22214", "urn:party:22214", "cdm:party", "cdm:party:", "cdm:PARTY:1"})
  void rejectsMalformedRefs(String value) {
    assertThatThrownBy(() -> CdmRef.parse(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> CdmRef.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresKindAndKey() {
    assertThatThrownBy(() -> new CdmRef(null, "1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CdmRef(PARTY, " ")).isInstanceOf(IllegalArgumentException.class);
  }
}
