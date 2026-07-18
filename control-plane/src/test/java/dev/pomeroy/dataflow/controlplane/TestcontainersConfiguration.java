package dev.pomeroy.dataflow.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A throwaway Postgres for {@code @SpringBootTest} contexts, so {@code ./mvnw verify}
 * needs Docker but never the compose world. Same image the compose world pins
 * (docs/versions.md).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:18.4");
	}

	/**
	 * Tests never poll live Kestra: the runs poller's one intended test seam is the M1
	 * gate walkthrough against the compose world (spec #10 explicitly keeps
	 * mocked-Kestra poller tests out).
	 */
	@Bean
	DynamicPropertyRegistrar runsPollerOff() {
		return registry -> registry.add("runs.poller.enabled", () -> "false");
	}
}
