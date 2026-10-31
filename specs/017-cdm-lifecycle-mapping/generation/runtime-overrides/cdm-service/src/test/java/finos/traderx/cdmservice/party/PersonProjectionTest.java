package finos.traderx.cdmservice.party;

import static org.assertj.core.api.Assertions.assertThat;

import cdm.base.staticdata.party.NaturalPerson;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import org.junit.jupiter.api.Test;

class PersonProjectionTest {

  private final ObjectMapper cdmObjectMapper = new CdmRuntimeConfig().cdmObjectMapper();

  private NaturalPerson throughJson(NaturalPerson person) throws Exception {
    return cdmObjectMapper.readValue(cdmObjectMapper.writeValueAsString(person), NaturalPerson.class);
  }

  /** Every people.json entry survives person -> CDM JSON -> person projection unchanged. */
  @Test
  void personProjectionRoundTripIsLossless() throws Exception {
    assertThat(PersonSeed.load()).hasSize(10);
    for (PersonSeed.SeedPerson seed : PersonSeed.load()) {
      NaturalPerson person = PartyMapper.toCdmPerson(seed.logonId(), seed.fullName(),
          seed.email(), seed.employeeId(), seed.department(), seed.photoUrl());
      NaturalPerson reread = throughJson(person);

      assertThat(reread).isEqualTo(person);
      assertThat(PartyMapper.toPersonProjection(reread)).isEqualTo(new PersonProjection(
          seed.logonId(), seed.fullName(), seed.email(), seed.employeeId(), seed.department(),
          seed.photoUrl(), "cdm:person:" + seed.logonId()));
    }
  }

  @Test
  void photoUrlIsATraderxSchemeIdentifier() {
    NaturalPerson person = PartyMapper.toCdmPerson("user01", "Alice Johnson",
        "alice.johnson@example.com", "E0001", "HR", "https://example.com/photos/user01.jpg");

    assertThat(PartyMapper.PHOTO_URL_SCHEME).isEqualTo("https://traderx.finos.org/photo-url");
    assertThat(person.getPersonId()).anySatisfy(id -> {
      assertThat(id.getValue().getIdentifier().getValue())
          .isEqualTo("https://example.com/photos/user01.jpg");
      assertThat(id.getValue().getIdentifier().getMeta().getScheme())
          .isEqualTo(PartyMapper.PHOTO_URL_SCHEME);
    });
    assertThat(PartyMapper.photoUrl(person)).isEqualTo("https://example.com/photos/user01.jpg");
    // The five-argument form (no photo) is unchanged.
    assertThat(PartyMapper.photoUrl(PartyMapper.toCdmPerson("x", "X", null, null, null))).isNull();
  }

  @Test
  void onlyPersonsWithFullDirectoryDetailsAreDirectoryPersons() {
    assertThat(PartyMapper.isDirectoryPerson(PartyMapper.toCdmPerson("user01", "Alice Johnson",
        "alice.johnson@example.com", "E0001", "HR", "https://example.com/photos/user01.jpg")))
        .isTrue();
    assertThat(PartyMapper.isDirectoryPerson(PartyMapper.identifiedPerson("adhoc1"))).isFalse();
    assertThat(PartyMapper.isDirectoryPerson(
        PartyMapper.toCdmPerson("x", "X Y", null, "E9", null, null))).isFalse();
  }

  @Test
  void identifierOnlyPersonProjectsWithNullDetails() {
    assertThat(PartyMapper.toPersonProjection(PartyMapper.identifiedPerson("adhoc1")))
        .isEqualTo(new PersonProjection("adhoc1", null, null, null, null, null,
            "cdm:person:adhoc1"));
  }
}
