package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfigModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;

/**
 * Registers {@link DataflowConfigModule} with the application's shared mapper, so the
 * REST layer parses node discrimination the same way the contract tests do.
 */
@Configuration(proxyBeanMethods = false)
class DataflowJacksonConfiguration {

	@Bean
	JacksonModule dataflowConfigModule() {
		return new DataflowConfigModule();
	}
}
