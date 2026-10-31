package finos.traderx.cdmservice.party;

import static org.assertj.core.api.Assertions.assertThat;

import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.party.NaturalPersonRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PersonIdentifierTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartyMapperTest {

  private final ObjectMapper cdmObjectMapper = new CdmRuntimeConfig().cdmObjectMapper();

  @Test
  void accountProjectionRoundTripIsLossless() throws Exception {
    Party party = PartyMapper.toCdmParty(22214, "Test Account 20");

    // Through the CDM JSON form too, since that is what the store keeps.
    Party reread = cdmObjectMapper.readValue(cdmObjectMapper.writeValueAsString(party), Party.class);

    assertThat(PartyMapper.toAccountProjection(reread))
        .isEqualTo(new AccountProjection(22214, "Test Account 20", "cdm:party:22214"));
    assertThat(reread).isEqualTo(party);
    assertThat(PartyMapper.toAccountProjection(PartyMapper.toCdmParty(7, null)))
        .isEqualTo(new AccountProjection(7, null, "cdm:party:7"));
  }

  @Test
  void accountPartyCarriesTheAccountIdUnderTheTraderxScheme() {
    Party party = PartyMapper.toCdmParty(22214, "Test Account 20");

    assertThat(party.getPartyId()).hasSize(1);
    assertThat(party.getPartyId().get(0).getIdentifier().getValue()).isEqualTo("22214");
    assertThat(party.getPartyId().get(0).getIdentifier().getMeta().getScheme())
        .isEqualTo("https://traderx.finos.org/account-id");
    assertThat(party.getName().getValue()).isEqualTo("Test Account 20");
    assertThat(party.getMeta().getExternalKey()).isEqualTo("22214");
  }

  @Test
  void venuePartyHasStableKey() {
    Party venue = PartyMapper.venueParty();

    assertThat(venue).isEqualTo(PartyMapper.venueParty());
    assertThat(PartyMapper.VENUE_KEY).isEqualTo("traderx-matching-engine");
    assertThat(PartyMapper.venueRef().toString()).isEqualTo("cdm:party:traderx-matching-engine");
    assertThat(venue.getMeta().getExternalKey()).isEqualTo("traderx-matching-engine");
    assertThat(venue.getName().getValue()).isEqualTo("TraderX Matching Engine");
    assertThat(venue.getPartyId().get(0).getIdentifier().getValue())
        .isEqualTo("traderx-matching-engine");
  }

  @Test
  void personCarriesLogonIdEmployeeIdNameEmailAndDepartment() {
    NaturalPerson person = PartyMapper.toCdmPerson("user01", "Alice Johnson",
        "alice.johnson@example.com", "E0001", "HR");

    assertThat(PartyMapper.logonId(person)).isEqualTo("user01");
    assertThat(person.getMeta().getExternalKey()).isEqualTo("user01");
    assertThat(person.getFirstName()).isEqualTo("Alice");
    assertThat(person.getSurname()).isEqualTo("Johnson");
    assertThat(person.getContactInformation().getEmail()).containsExactly("alice.johnson@example.com");
    assertThat(person.getPersonId()).anySatisfy(id -> {
      assertThat(id.getValue().getIdentifierType()).isEqualTo(PersonIdentifierTypeEnum.EMPL);
      assertThat(id.getValue().getIdentifier().getValue()).isEqualTo("E0001");
    });
    assertThat(PartyMapper.fullName(person)).isEqualTo("Alice Johnson");
    assertThat(PartyMapper.employeeId(person)).isEqualTo("E0001");
    assertThat(PartyMapper.department(person)).isEqualTo("HR");
    assertThat(PartyMapper.email(person)).isEqualTo("alice.johnson@example.com");
    assertThat(PartyMapper.toCdmPerson("x", "Mary Ann  Smith", null, null, null))
        .satisfies(p -> assertThat(PartyMapper.fullName(p)).isEqualTo("Mary Ann  Smith"));
  }

  @Test
  void relatedPersonsAreTradersReferencedByLogonId() throws Exception {
    NaturalPerson alice = PartyMapper.toCdmPerson("user01", "Alice Johnson",
        "alice.johnson@example.com", "E0001", "HR");
    Party party = PartyMapper.toCdmParty(22214, "Test Account 20",
        List.of(PartyMapper.identifiedPerson("user03"), alice));

    Party reread = cdmObjectMapper.readValue(cdmObjectMapper.writeValueAsString(party), Party.class);

    assertThat(PartyMapper.relatedLogonIds(reread)).containsExactly("user01", "user03");
    assertThat(reread.getPerson()).hasSize(2);
    assertThat(reread.getPersonRole()).allSatisfy(role -> assertThat(role.getRole())
        .extracting(r -> r.getValue()).containsExactly(NaturalPersonRoleEnum.TRADER));
    assertThat(reread.getPersonRole().get(0).getPersonReference().getExternalReference())
        .isEqualTo("user01");
    assertThat(PartyMapper.toAccountUserProjections(reread)).containsExactly(
        new AccountUserProjection(22214, "user01", "cdm:person:user01"),
        new AccountUserProjection(22214, "user03", "cdm:person:user03"));
  }
}
