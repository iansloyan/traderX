package finos.traderx.cdmservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import finos.traderx.cdmservice.master.CdmMaster;
import finos.traderx.cdmservice.web.HealthController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@SpringBootTest
@AutoConfigureMockMvc
class CdmServiceApplicationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  @Qualifier("cdmInjector")
  private Injector cdmInjector;

  @Autowired
  @Qualifier("cdmObjectMapper")
  private ObjectMapper cdmObjectMapper;

  @Autowired
  private RequestMappingHandlerAdapter handlerAdapter;

  /** Both masters seed synchronously on ApplicationReady in tests, so health is "ok". */
  @Test
  void healthReportsServiceAndCdmVersion() throws Exception {
    mockMvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(content().json(
            "{\"status\":\"ok\",\"service\":\"cdm-service\",\"cdmVersion\":\"7.8.0\"}",
            JsonCompareMode.STRICT));
  }

  @Test
  void exposesCdmRuntimeBeans() {
    assertThat(cdmInjector).isNotNull();
    assertThat(cdmObjectMapper).isNotNull();
  }

  @Test
  @SuppressWarnings("removal")
  void cdmObjectMapperIsNotUsedBySpringMvc() {
    List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();

    // JSON responses go through Spring Boot's Jackson 3 converter, not the CDM mapper.
    HttpMessageConverter<?> jsonWriter = converters.stream()
        .filter(c -> c.canWrite(Map.class, MediaType.APPLICATION_JSON))
        .findFirst()
        .orElseThrow();
    assertThat(jsonWriter).isInstanceOf(JacksonJsonHttpMessageConverter.class);

    // rune-common puts Jackson 2 YAML/XML dataformats on the classpath, so Spring also
    // registers Jackson 2 YAML/XML converters; they must use their own default mappers.
    assertThat(converters)
        .filteredOn(AbstractJackson2HttpMessageConverter.class::isInstance)
        .allSatisfy(c -> assertThat(((AbstractJackson2HttpMessageConverter) c).getObjectMapper())
            .isNotSameAs(cdmObjectMapper));
  }

  @Test
  void healthIsStartingUntilEveryMasterIsReady() throws Exception {
    CdmMaster ready = () -> true;
    CdmMaster starting = () -> false;

    MockMvcBuilders.standaloneSetup(new HealthController(List.of(ready, starting))).build()
        .perform(get("/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().json("{\"status\":\"starting\"}", JsonCompareMode.STRICT));
    MockMvcBuilders.standaloneSetup(new HealthController(List.of(ready, ready))).build()
        .perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));
  }
}
