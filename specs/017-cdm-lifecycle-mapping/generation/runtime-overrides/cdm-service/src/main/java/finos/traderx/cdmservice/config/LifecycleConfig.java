package finos.traderx.cdmservice.config;

import com.google.inject.Injector;
import finos.traderx.cdmservice.lifecycle.ExecutionMapper;
import finos.traderx.cdmservice.lifecycle.LifecycleReferenceData;
import finos.traderx.cdmservice.lifecycle.WorkflowMapper;
import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.security.SecurityMaster;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The lifecycle mappers (Task 9), reading reference data from the CDM masters. */
@Configuration(proxyBeanMethods = false)
public class LifecycleConfig {

  @Bean
  public LifecycleReferenceData lifecycleReferenceData(SecurityMaster securities,
      PartyMaster parties) {
    return LifecycleReferenceData.of(securities, parties);
  }

  @Bean
  public WorkflowMapper workflowMapper(@Qualifier("cdmInjector") Injector cdmInjector,
      LifecycleReferenceData referenceData) {
    return new WorkflowMapper(cdmInjector, referenceData);
  }

  @Bean
  public ExecutionMapper executionMapper(@Qualifier("cdmInjector") Injector cdmInjector,
      LifecycleReferenceData referenceData) {
    return new ExecutionMapper(cdmInjector, referenceData);
  }
}
