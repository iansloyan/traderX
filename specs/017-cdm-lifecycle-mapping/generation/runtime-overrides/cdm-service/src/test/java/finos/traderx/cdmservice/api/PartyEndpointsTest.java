package finos.traderx.cdmservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cdm.base.staticdata.party.Party;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.party.PartyMaster;
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

/** Full context: the party master is seeded from the H2 account fixture on ApplicationReady. */
@SpringBootTest
@AutoConfigureMockMvc
class PartyEndpointsTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PartyMaster partyMaster;

  @Autowired
  @Qualifier("cdmObjectMapper")
  private ObjectMapper cdmObjectMapper;

  @Test
  void seedsOnApplicationReady() {
    assertThat(partyMaster.isReady()).isTrue();
  }

  @Test
  void listsAndGetsAccountProjections() throws Exception {
    mockMvc.perform(get("/projections/accounts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(22214))
        .andExpect(jsonPath("$[0].displayName").value("Test Account 20"))
        .andExpect(jsonPath("$[0].cdmRef").value("cdm:party:22214"));
    mockMvc.perform(get("/projections/accounts/52355"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"id\":52355,\"displayName\":\"Big Corporate Fund\","
            + "\"cdmRef\":\"cdm:party:52355\"}", JsonCompareMode.STRICT));
    mockMvc.perform(get("/projections/accounts/1")).andExpect(status().isNotFound());
  }

  @Test
  void createsAndUpdatesAccounts() throws Exception {
    String created = mockMvc.perform(post("/projections/accounts")
            .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Posted Fund\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Posted Fund"))
        .andReturn().getResponse().getContentAsString();
    int id = Integer.parseInt(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
    assertThat(id).isGreaterThanOrEqualTo(65000);

    mockMvc.perform(put("/projections/accounts/" + id)
            .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Renamed Fund\"}"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"id\":" + id + ",\"displayName\":\"Renamed Fund\","
            + "\"cdmRef\":\"cdm:party:" + id + "\"}", JsonCompareMode.STRICT));
    mockMvc.perform(get("/projections/accounts/" + id))
        .andExpect(jsonPath("$.displayName").value("Renamed Fund"));

    // POST with a client-supplied id is the old upsert.
    mockMvc.perform(post("/projections/accounts")
            .contentType(MediaType.APPLICATION_JSON).content("{\"id\":94242,\"displayName\":\"Given\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(94242));
  }

  @Test
  void listsFiltersAndAddsAccountUsers() throws Exception {
    mockMvc.perform(get("/projections/accountusers").param("accountId", "22214"))
        .andExpect(status().isOk())
        .andExpect(content().json("[{\"accountId\":22214,\"username\":\"user01\","
            + "\"cdmRef\":\"cdm:person:user01\"},{\"accountId\":22214,\"username\":\"user03\","
            + "\"cdmRef\":\"cdm:person:user03\"}]", JsonCompareMode.STRICT));

    mockMvc.perform(post("/projections/accountusers").contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":52355,\"username\":\"user04\"}"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"accountId\":52355,\"username\":\"user04\","
            + "\"cdmRef\":\"cdm:person:user04\"}", JsonCompareMode.STRICT));
    mockMvc.perform(get("/projections/accountusers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.accountId == 52355 && @.username == 'user04')]").exists());

    mockMvc.perform(post("/projections/accountusers").contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":99999,\"username\":\"user04\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void cdmPartyEndpointResolvesFullPersonsFromMaster() throws Exception {
    String json = mockMvc.perform(get("/cdm/parties/22214"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.partyId[0].identifier.value").value("22214"))
        .andExpect(jsonPath("$.person[0].firstName").value("Alice"))
        .andExpect(jsonPath("$.person[0].contactInformation.email[0]")
            .value("alice.johnson@example.com"))
        .andExpect(jsonPath("$.personRole[0].role[0].value").value("Trader"))
        .andReturn().getResponse().getContentAsString();
    Party party = cdmObjectMapper.readValue(json, Party.class);
    assertThat(PartyMapper.relatedLogonIds(party)).contains("user01", "user03");

    mockMvc.perform(get("/cdm/parties/traderx-matching-engine"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name.value").value("TraderX Matching Engine"));
    mockMvc.perform(get("/cdm/parties/31337")).andExpect(status().isNotFound());
  }

  @Test
  void clientErrorsAre400And409NotServiceUnavailable() throws Exception {
    mockMvc.perform(post("/projections/accounts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"displayName\":\"" + "x".repeat(51) + "\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/projections/accounts").contentType(MediaType.APPLICATION_JSON)
        .content("{not json")).andExpect(status().isBadRequest());
    mockMvc.perform(post("/projections/accounts").contentType(MediaType.APPLICATION_JSON)
        .content("{\"id\":\"abc\"}")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/projections/accounts/abc")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/projections/accountusers").param("accountId", "abc"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/projections/accountusers").contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":22214,\"username\":\"" + "u".repeat(16) + "\"}"))
        .andExpect(status().isBadRequest());

    PartyMaster conflicted = mock(PartyMaster.class);
    when(conflicted.createAccount("x"))
        .thenThrow(new PartyMaster.AccountIdConflictException("no free id"));
    MockMvcBuilders.standaloneSetup(new ProjectionController(mock(SecurityMaster.class), conflicted))
        .build()
        .perform(post("/projections/accounts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"displayName\":\"x\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void accountProjectionsAre503UntilTheMasterIsSeeded() throws Exception {
    PartyMaster notReady = mock(PartyMaster.class);
    when(notReady.isReady()).thenReturn(false);
    when(notReady.accounts()).thenThrow(new PartyMaster.NotReadyException());
    when(notReady.findAccount(22214)).thenThrow(new PartyMaster.NotReadyException());
    when(notReady.accountUsers(null)).thenThrow(new PartyMaster.NotReadyException());
    when(notReady.createAccount("x")).thenThrow(new PartyMaster.NotReadyException());
    MockMvc standalone = MockMvcBuilders.standaloneSetup(
        new ProjectionController(mock(SecurityMaster.class), notReady)).build();

    standalone.perform(get("/projections/accounts")).andExpect(status().isServiceUnavailable());
    standalone.perform(get("/projections/accounts/22214")).andExpect(status().isServiceUnavailable());
    standalone.perform(get("/projections/accountusers")).andExpect(status().isServiceUnavailable());
    standalone.perform(post("/projections/accounts").contentType(MediaType.APPLICATION_JSON)
        .content("{\"displayName\":\"x\"}")).andExpect(status().isServiceUnavailable());
  }

  @Test
  void storeFailuresOnWritesAre503() throws Exception {
    PartyMaster failing = mock(PartyMaster.class);
    when(failing.createAccount("x")).thenThrow(new IllegalStateException("database down"));
    MockMvc standalone = MockMvcBuilders.standaloneSetup(
        new ProjectionController(mock(SecurityMaster.class), failing)).build();

    standalone.perform(post("/projections/accounts").contentType(MediaType.APPLICATION_JSON)
        .content("{\"displayName\":\"x\"}")).andExpect(status().isServiceUnavailable());
  }
}
