package dev.pomeroy.dataflow.controlplane.runs.internal;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

/**
 * Run ids are server-generated, never client-supplied — the same rule the dataflow
 * spine follows.
 */
@Configuration(proxyBeanMethods = false)
class RunsPersistenceConfiguration {

	@Bean
	BeforeConvertCallback<RunEntity> serverGeneratedRunId() {
		return entity -> entity.id() != null ? entity : entity.withId(UUID.randomUUID());
	}
}
