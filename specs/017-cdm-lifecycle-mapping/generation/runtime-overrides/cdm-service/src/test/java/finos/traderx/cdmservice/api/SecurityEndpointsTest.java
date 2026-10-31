package finos.traderx.cdmservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cdm.base.staticdata.asset.common.Security;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.security.DemoIdentifiers;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.security.SecurityMaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Full context: the security master is seeded on ApplicationReadyEvent. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityEndpointsTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private SecurityMaster securityMaster;

  @Autowired
  @Qualifier("cdmObjectMapper")
  private ObjectMapper cdmObjectMapper;

  @Test
  void seedsOnApplicationReady() {
    assertThat(securityMaster.isReady()).isTrue();
    assertThat(securityMaster.all()).hasSize(509);
  }

  @Test
  void listsStockProjectionsInSeedOrder() throws Exception {
    mockMvc.perform(get("/projections/stocks"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(509))
        .andExpect(jsonPath("$[0].ticker").value("MMM"))
        .andExpect(jsonPath("$[0].companyName").value("3M"))
        .andExpect(jsonPath("$[0].cdmRef").value("cdm:security:MMM"));
  }

  @Test
  void returnsOneStockProjection() throws Exception {
    mockMvc.perform(get("/projections/stocks/IBM"))
        .andExpect(status().isOk())
        .andExpect(content().json(
            "{\"ticker\":\"IBM\",\"companyName\":\"IBM\","
                + "\"cdmRef\":\"cdm:security:IBM\"}",
            JsonCompareMode.STRICT));
  }

  @Test
  void dottedTickersAreNotTreatedAsFileExtensions() throws Exception {
    mockMvc.perform(get("/projections/stocks/BRK.B"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticker").value("BRK.B"))
        .andExpect(jsonPath("$.cdmRef").value("cdm:security:BRK.B"));
    mockMvc.perform(get("/cdm/securities/BF.B")).andExpect(status().isOk());
  }

  @Test
  void unknownTickerIs404() throws Exception {
    mockMvc.perform(get("/projections/stocks/NOPE")).andExpect(status().isNotFound());
    mockMvc.perform(get("/cdm/securities/NOPE")).andExpect(status().isNotFound());
  }

  @Test
  void servesFullCdmSecurityJsonWrittenByTheCdmMapper() throws Exception {
    String json = mockMvc.perform(get("/cdm/securities/IBM"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.securityType").value("Equity"))
        .andExpect(jsonPath("$.identifier[?(@.identifierType == 'ISIN')].identifier.value")
            .value(DemoIdentifiers.isin("IBM")))
        .andReturn().getResponse().getContentAsString();

    assertThat(cdmObjectMapper.readValue(json, Security.class))
        .isEqualTo(SecurityMapper.toCdm("IBM", "IBM"));
  }

  @Test
  void projectionsAre503UntilTheMasterIsSeeded() throws Exception {
    SecurityMaster notReady = mock(SecurityMaster.class);
    when(notReady.isReady()).thenReturn(false);
    MockMvc standalone = MockMvcBuilders.standaloneSetup(
        new ProjectionController(notReady, mock(PartyMaster.class))).build();

    standalone.perform(get("/projections/stocks")).andExpect(status().isServiceUnavailable());
    standalone.perform(get("/projections/stocks/IBM")).andExpect(status().isServiceUnavailable());
  }
}
