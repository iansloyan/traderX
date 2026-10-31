package finos.traderx.cdmservice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.asset.common.DebtType;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.asset.common.SecurityTypeEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import cdm.event.common.BusinessEvent;
import cdm.event.common.ExecutionInstruction;
import cdm.event.common.Instruction;
import cdm.event.common.PrimitiveInstruction;
import cdm.event.common.QuantityChangeInstruction;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.party.PersonSeed;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.security.SecuritySeed;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import com.rosetta.model.lib.records.Date;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CdmValidatorTest {

  private static final CdmValidator VALIDATOR =
      new CdmValidator(new CdmRuntimeConfig().cdmInjector());

  @Test
  void partyWithoutIdentifierIsInvalid() {
    Party party = Party.builder().setNameValue("No Identifier").build();

    CdmValidationResult result = VALIDATOR.validate(party);

    assertThat(result.status()).isEqualTo(Status.INVALID);
    // Human-readable: the path of the failing object and the reason (cardinality of partyId).
    assertThat(result.messages()).anySatisfy(message -> assertThat(message)
        .startsWith("Party")
        .contains("partyId"));
  }

  /** Conditions run: an equity with a debt type breaks the SecurityDebtSubType data rule. */
  @Test
  void dataRuleConditionFailureIsReported() {
    Security security = SecurityMapper.toCdm("IBM", "IBM").toBuilder()
        .setSecurityType(SecurityTypeEnum.EQUITY)
        .setDebtType(DebtType.builder())
        .build();

    CdmValidationResult result = VALIDATOR.validate(security);

    assertThat(result.status()).isEqualTo(Status.INVALID);
    assertThat(result.messages()).anySatisfy(m -> assertThat(m)
        .contains("DATA_RULE SecurityDebtSubType"));
  }

  /** The walk recurses: a failure inside a nested object is reported with its path. */
  @Test
  void nestedPathIsReportedInMessage() {
    Party party = Party.builder().setNameValue("Nested")
        .addPartyId(PartyIdentifier.builder())
        .build();

    CdmValidationResult result = VALIDATOR.validate(party);

    assertThat(result.status()).isEqualTo(Status.INVALID);
    assertThat(result.messages()).anySatisfy(m -> assertThat(m)
        .startsWith("Party.partyId(0)")
        .contains("CARDINALITY PartyIdentifier")
        .contains("identifier"));
  }

  @Test
  void validPartyIsValid() {
    Party party = PartyMapper.toCdmParty(22214, "Test Account 20",
        List.of(PartyMapper.identifiedPerson("user01"), PartyMapper.identifiedPerson("user03")));

    CdmValidationResult result = VALIDATOR.validate(party);

    assertThat(result.messages()).isEmpty();
    assertThat(result.status()).isEqualTo(Status.VALID);
  }

  @Test
  void nonExecutionEventIsNotQualified() {
    BusinessEvent event = BusinessEvent.builder()
        .setEventDate(Date.of(2026, 10, 29))
        .addInstruction(Instruction.builder()
            .setPrimitiveInstruction(PrimitiveInstruction.builder()
                .setQuantityChange(QuantityChangeInstruction.builder())))
        .build();

    CdmValidationResult result = VALIDATOR.validateExecution(event);

    assertThat(result.status()).isEqualTo(Status.NOT_QUALIFIED);
    assertThat(result.messages()).first().isEqualTo("Qualify_Execution=false");
  }

  @Test
  void emptyEventIsNotQualified() {
    CdmValidationResult result = VALIDATOR.validateExecution(BusinessEvent.builder().build());

    assertThat(result.status()).isEqualTo(Status.NOT_QUALIFIED);
    assertThat(result.messages()).first().isEqualTo("Qualify_Execution=false");
  }

  @Test
  void executionEventPassesQualification() {
    // Only an execution primitive and no intent: Qualify_Execution is true. The event is far
    // from complete, so it may still be INVALID, but never NOT_QUALIFIED.
    BusinessEvent event = BusinessEvent.builder()
        .setEventDate(Date.of(2026, 10, 29))
        .addInstruction(Instruction.builder()
            .setPrimitiveInstruction(PrimitiveInstruction.builder()
                .setExecution(ExecutionInstruction.builder())))
        .build();

    CdmValidationResult result = VALIDATOR.validateExecution(event);

    assertThat(result.status()).isNotEqualTo(Status.NOT_QUALIFIED);
    assertThat(result.messages()).doesNotContain("Qualify_Execution=false");
    // Qualification really ran and answered true (an exception would add this message).
    assertThat(result.messages()).noneSatisfy(m -> assertThat(m).startsWith("qualification error"));
  }

  @Test
  void nullObjectIsInvalidNotThrown() {
    assertThat(VALIDATOR.validate(null).status()).isEqualTo(Status.INVALID);
    assertThat(VALIDATOR.validateExecution(null).status()).isEqualTo(Status.INVALID);
  }

  /** Every object the masters seed must come out VALID with the real validator. */
  @Test
  void everySeededSecurityIsValid() {
    List<String> failures = new ArrayList<>();
    for (SecuritySeed.SeedStock seed : SecuritySeed.load()) {
      CdmValidationResult result =
          VALIDATOR.validate(SecurityMapper.toCdm(seed.ticker(), seed.companyName()));
      if (result.status() != Status.VALID) {
        failures.add(seed.ticker() + ": " + result.messages());
      }
    }
    assertThat(failures).isEmpty();
  }

  @Test
  void everySeededPartyAndPersonIsValid() {
    List<String> failures = new ArrayList<>();
    for (PersonSeed.SeedPerson seed : PersonSeed.load()) {
      NaturalPerson person = PartyMapper.toCdmPerson(seed.logonId(), seed.fullName(),
          seed.email(), seed.employeeId(), seed.department(), seed.photoUrl());
      collect(failures, "person " + seed.logonId(), VALIDATOR.validate(person));
    }
    collect(failures, "identifier-only person",
        VALIDATOR.validate(PartyMapper.identifiedPerson("user99")));
    collect(failures, "venue", VALIDATOR.validate(PartyMapper.venueParty()));
    collect(failures, "account without users",
        VALIDATOR.validate(PartyMapper.toCdmParty(52355, "Big Corporate Fund")));
    collect(failures, "account without name", VALIDATOR.validate(PartyMapper.toCdmParty(7, null)));
    assertThat(failures).isEmpty();
  }

  private static void collect(List<String> failures, String what, CdmValidationResult result) {
    if (result.status() != Status.VALID) {
      failures.add(what + ": " + result.messages());
    }
  }
}
