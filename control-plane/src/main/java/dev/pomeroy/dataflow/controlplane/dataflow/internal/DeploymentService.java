package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler.Compiled;
import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler.Rejected;
import dev.pomeroy.dataflow.controlplane.dataflow.Engine;
import dev.pomeroy.dataflow.controlplane.dataflow.EngineDeployments;
import dev.pomeroy.dataflow.controlplane.dataflow.ExecutionModel;
import dev.pomeroy.dataflow.controlplane.dataflow.OrchestratorFlows;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * The Deploy act (spec #10): semantic validation → compile → freeze an immutable
 * Deployment → publish the flow. The Kestra push happens inside the freezing
 * transaction, so a push failure rolls the Deployment back — Kestra and the spine
 * never disagree about what version is live.
 */
@Service
class DeploymentService {

	private final DeploymentRepository deployments;

	private final DeploymentCompiler compiler;

	private final OrchestratorFlows orchestrator;

	private final EngineDeployments engineDeployments;

	DeploymentService(DeploymentRepository deployments, DeploymentCompiler compiler,
			OrchestratorFlows orchestrator, EngineDeployments engineDeployments) {
		this.deployments = deployments;
		this.compiler = compiler;
		this.orchestrator = orchestrator;
		this.engineDeployments = engineDeployments;
	}

	@Transactional
	DeploymentEntity deploy(DataflowEntity dataflow) {
		int version = nextVersion(dataflow.id());
		return switch (compiler.compile(dataflow.slug(), dataflow.config(), version)) {
			case Rejected rejected -> throw new SemanticViolationsException(rejected.violations());
			case Compiled(String planJson, String flowYaml, String engineFlowDefinition) -> {
				Optional<DeploymentEntity> superseded =
						deployments.findByDataflowIdAndActiveTrue(dataflow.id());
				superseded.ifPresent(active -> deployments.save(active.deactivated()));
				DeploymentEntity frozen;
				try {
					frozen = deployments.save(new DeploymentEntity(null, dataflow.id(), version,
							dataflow.config(), new PlanSnapshot(planJson), Instant.now(), true));
				}
				catch (DuplicateKeyException e) {
					// The unique (dataflow_id, version) constraint is the authority on
					// concurrent deploys — the loser gets a problem detail, not a 500.
					throw new ErrorResponseException(HttpStatus.CONFLICT,
							ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
									"Another deploy of '%s' won version %d — retry"
											.formatted(dataflow.slug(), version)),
							e);
				}
				// Engine-side artifacts first (M4.4): a refused NiFi upload rolls the
				// whole deploy back before the Orchestrator ever sees the new flow, so
				// the Kestra push stays deploy's final act.
				if (engineFlowDefinition != null) {
					// nifi: put wholly replaces the same-slug artifacts, so a nifi-over-
					// nifi or hop-over-nifi flip needs no separate teardown.
					push(() -> engineDeployments.put(dataflow.slug(), version,
							engineFlowDefinition));
				}
				else if (superseded.map(DeploymentEntity::config)
						.filter(DeploymentService::hasServerSideEngineState).isPresent()) {
					// Flipping a nifi Deployment to an engine with no server-side state
					// (M4.5): put never runs, so tear the orphaned process group and
					// parameter context down here before the Orchestrator's new flow.
					push(() -> engineDeployments.remove(dataflow.slug()));
				}
				push(() -> orchestrator.put(dataflow.slug(), flowYaml));
				yield frozen;
			}
		};
	}

	/**
	 * Undeploy: the Orchestrator forgets the flow entirely — it holds exactly the
	 * actively deployed Dataflows — while every frozen snapshot stays readable forever.
	 */
	@Transactional
	void undeploy(DataflowEntity dataflow) {
		DeploymentEntity active = deployments.findByDataflowIdAndActiveTrue(dataflow.id())
				.orElseThrow(() -> new ErrorResponseException(HttpStatus.CONFLICT,
						ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
								"Dataflow '%s' is not deployed".formatted(dataflow.slug())),
						null));
		deployments.save(active.deactivated());
		// Engine-side residue goes with the flow (M4.5): a nifi Deployment's process
		// group and parameter context are torn down, then Kestra forgets the flow. Hop
		// held nothing server-side, so its teardown is skipped entirely — undeploy of a
		// hop feed never reaches for NiFi.
		if (hasServerSideEngineState(active.config())) {
			push(() -> engineDeployments.remove(dataflow.slug()));
		}
		push(() -> orchestrator.remove(dataflow.slug()));
	}

	/**
	 * Whether a Deployment's Engine holds server-side state that a supersession or
	 * undeploy must tear down — today only the {@code nifi × server} cell (its process
	 * group and parameter context). Mirrors the compiler's gate on emitting an engine
	 * flow definition at all, so the two never disagree about which Deployments have
	 * engine-side residue.
	 */
	private static boolean hasServerSideEngineState(DataflowConfig config) {
		return config.engine() == Engine.NIFI
				&& config.executionModel() == ExecutionModel.SERVER;
	}

	boolean deployed(UUID dataflowId) {
		return deployments.findByDataflowIdAndActiveTrue(dataflowId).isPresent();
	}

	/**
	 * Orchestrator and Engine calls run inside the freezing transaction: a failed
	 * push becomes a 502 problem detail and rolls the lifecycle change back, so the
	 * spine, Kestra and the Engine never disagree about what is live.
	 */
	private void push(Runnable call) {
		try {
			call.run();
		}
		catch (RuntimeException e) {
			throw new ErrorResponseException(HttpStatus.BAD_GATEWAY,
					ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
							"A downstream system did not accept the change: " + e.getMessage()),
					e);
		}
	}

	/**
	 * Strictly increasing per Dataflow, never reused: the next version after the
	 * highest ever frozen, whether or not that one is still active.
	 */
	private int nextVersion(UUID dataflowId) {
		return deployments.findByDataflowIdOrderByVersionDesc(dataflowId).stream()
				.findFirst().map(DeploymentEntity::version).orElse(0) + 1;
	}
}
