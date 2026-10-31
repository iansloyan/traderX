package finos.traderx.cdmservice.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.security.SecurityMaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The person directory people-service projects (Ruling R20: full directory persons only). */
@SpringBootTest
@AutoConfigureMockMvc
class PeopleEndpointsTest {

  private static final String ALICE = "{\"logonId\":\"user01\",\"fullName\":\"Alice Johnson\","
      + "\"email\":\"alice.johnson@example.com\",\"employeeId\":\"E0001\",\"department\":\"HR\","
      + "\"photoUrl\":\"https://example.com/photos/user01.jpg\",\"cdmRef\":\"cdm:person:user01\"}";

  @Autowired
  private MockMvc mockMvc;

  @Test
  void listsTheDirectoryInLogonIdOrder() throws Exception {
    mockMvc.perform(get("/projections/people"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[0].logonId").value("user01"))
        .andExpect(jsonPath("$[9].logonId").value("user10"))
        .andExpect(jsonPath("$[0].cdmRef").value("cdm:person:user01"));
  }

  @Test
  void getsOnePersonByLogonIdWithEveryField() throws Exception {
    mockMvc.perform(get("/projections/people/user01"))
        .andExpect(status().isOk())
        .andExpect(content().json(ALICE, JsonCompareMode.STRICT));
    mockMvc.perform(get("/projections/people/nobody99")).andExpect(status().isNotFound());
  }

  @Test
  void findsByEmployeeId() throws Exception {
    mockMvc.perform(get("/projections/people").param("employeeId", "E0001"))
        .andExpect(status().isOk())
        .andExpect(content().json("[" + ALICE + "]", JsonCompareMode.STRICT));
    mockMvc.perform(get("/projections/people").param("employeeId", "E9999"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]", JsonCompareMode.STRICT));
  }

  @Test
  void searchMatchesLogonIdOrFullNameCaseSensitively() throws Exception {
    mockMvc.perform(get("/projections/people").param("search", "Alice"))
        .andExpect(status().isOk())
        .andExpect(content().json("[" + ALICE + "]", JsonCompareMode.STRICT));
    mockMvc.perform(get("/projections/people").param("search", "user1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].logonId").value(org.hamcrest.Matchers.contains("user10")));
    mockMvc.perform(get("/projections/people").param("search", "alice"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]", JsonCompareMode.STRICT));
  }

  @Test
  void identifierOnlyPersonsAreNotInTheDirectory() throws Exception {
    mockMvc.perform(post("/projections/accountusers").contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":52355,\"username\":\"adhoc-dir1\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/projections/people/adhoc-dir1")).andExpect(status().isNotFound());
    mockMvc.perform(get("/projections/people").param("search", "adhoc-dir"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]", JsonCompareMode.STRICT));
    mockMvc.perform(get("/projections/people"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(10));
  }

  @Test
  void peopleProjectionsAre503UntilTheMasterIsSeeded() throws Exception {
    PartyMaster notReady = mock(PartyMaster.class);
    when(notReady.people()).thenThrow(new PartyMaster.NotReadyException());
    when(notReady.searchPeople("Ali")).thenThrow(new PartyMaster.NotReadyException());
    when(notReady.findPersonByEmployeeId("E0001")).thenThrow(new PartyMaster.NotReadyException());
    when(notReady.findDirectoryPerson("user01")).thenThrow(new PartyMaster.NotReadyException());
    MockMvc standalone = MockMvcBuilders.standaloneSetup(
        new ProjectionController(mock(SecurityMaster.class), notReady)).build();

    standalone.perform(get("/projections/people")).andExpect(status().isServiceUnavailable());
    standalone.perform(get("/projections/people").param("search", "Ali"))
        .andExpect(status().isServiceUnavailable());
    standalone.perform(get("/projections/people").param("employeeId", "E0001"))
        .andExpect(status().isServiceUnavailable());
    standalone.perform(get("/projections/people/user01"))
        .andExpect(status().isServiceUnavailable());
  }
}
