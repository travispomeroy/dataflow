package dev.pomeroy.dataflow.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The module boundaries of the control plane, enforced before any real code exists
 * (M0.8). Every planned module from docs/PLAN.md must be present, and no module may
 * violate Spring Modulith's access rules (only another module's API packages may be
 * referenced, no cycles).
 *
 * <p>The plan names the per-engine compilers {@code compiler-kestra}, {@code
 * compiler-nifi} and {@code compiler-hop}; Java package names cannot contain hyphens,
 * so they are concatenated here.
 */
class ModularityTests {

	static final String[] PLANNED_MODULES = {
			"catalog",
			"compiler",
			"compilerhop",
			"compilerkestra",
			"compilernifi",
			"dataflow",
			"runner",
			"runs",
	};

	ApplicationModules modules = ApplicationModules.of(ControlPlaneApplication.class);

	@Test
	void allPlannedModulesExist() {
		assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
				.containsExactlyInAnyOrder(PLANNED_MODULES);
	}

	@Test
	void modulesRespectBoundaries() {
		modules.verify();
	}
}
