package finos.traderx.cdmservice;

import static org.assertj.core.api.Assertions.assertThat;

import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import cdm.event.workflow.functions.Create_ProposedWorkflowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CdmRuntimeConfigTest {

  private static Injector injector;
  private static ObjectMapper cdmObjectMapper;

  @BeforeAll
  static void createRuntime() {
    CdmRuntimeConfig config = new CdmRuntimeConfig();
    injector = config.cdmInjector();
    cdmObjectMapper = config.cdmObjectMapper();
  }

  @Test
  void injectorCreatesCdmFunctions() {
    assertThat(injector.getInstance(Create_ProposedWorkflowStep.class)).isNotNull();
  }

  /**
   * CDM 7.8.0 generated functions use {@code javax.inject.Inject}. Guice 7 only honours
   * {@code jakarta.inject}, so if dependency resolution drifts to Guice 7 the function is still
   * created but its injected collaborators stay null and it fails later at evaluate time.
   */
  @Test
  void injectorPopulatesInjectedFunctionDependencies() throws Exception {
    Create_ProposedWorkflowStep function = injector.getInstance(Create_ProposedWorkflowStep.class);
    for (String fieldName : new String[] {"conditionValidator", "objectValidator"}) {
      Field field = Create_ProposedWorkflowStep.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      assertThat(field.get(function)).as(fieldName + " injected").isNotNull();
    }
  }

  @Test
  void cdmObjectMapperRoundTripsCdmObjects() throws Exception {
    Party party = Party.builder()
        .setNameValue("TraderX Matching Engine")
        .addPartyId(PartyIdentifier.builder().setIdentifierValue("traderx-matching-engine"))
        .build();

    String json = cdmObjectMapper.writeValueAsString(party);
    Party parsed = cdmObjectMapper.readValue(json, Party.class);

    assertThat(json).contains("\"name\"").contains("TraderX Matching Engine");
    assertThat(parsed).isEqualTo(party);
  }
}
