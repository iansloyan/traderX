package finos.traderx.cdmservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import org.finos.cdm.CdmRuntimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the FINOS CDM (cdm-java) runtime into Spring.
 *
 * <ul>
 *   <li>{@code cdmInjector}: the Guice injector CDM functions are obtained from
 *       ({@code cdmInjector.getInstance(Create_ProposedWorkflowStep.class)} etc.).</li>
 *   <li>{@code cdmObjectMapper}: the Rosetta/Rune JSON mapper from rune-common
 *       ({@link RosettaObjectMapper#getNewRosettaObjectMapper()}), the mapper that reads and
 *       writes CDM objects in their canonical JSON form. It is a Jackson 2
 *       ({@code com.fasterxml.jackson}) mapper.</li>
 * </ul>
 *
 * <p>Spring MVC on Spring Boot 4 serialises with Jackson 3 ({@code tools.jackson}) and its own
 * auto-configured {@code JsonMapper}, so this bean is never picked up as the MVC mapper.
 * Controllers that return CDM JSON must write it with {@code cdmObjectMapper} explicitly.
 */
@Configuration(proxyBeanMethods = false)
public class CdmRuntimeConfig {

  /** The cdm-java version this service is built against (kept in step with build.gradle). */
  public static final String CDM_VERSION = "7.8.0";

  @Bean
  public Injector cdmInjector() {
    return Guice.createInjector(new CdmRuntimeModule());
  }

  @Bean
  public ObjectMapper cdmObjectMapper() {
    return RosettaObjectMapper.getNewRosettaObjectMapper();
  }
}
