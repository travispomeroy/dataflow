package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler;
import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler.Compiled;
import dev.pomeroy.dataflow.controlplane.dataflow.DeploymentCompiler.Rejected;
import dev.pomeroy.dataflow.controlplane.dataflow.OrchestratorFlows;
import java.time.Instant;
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

	DeploymentService(DeploymentRepository deployments, DeploymentCompiler compiler,
			OrchestratorFlows orchestrator) {
		this.deployments = deployments;
		this.compiler = compiler;
		this.orchestrator = orchestrator;
	}

	@Transactional
	DeploymentEntity deploy(DataflowEntity dataflow) {
		int version = nextVersion(dataflow.id());
		return switch (compiler.compile(dataflow.slug(), dataflow.config(), version)) {
			case Rejected rejected -> throw new SemanticViolationsException(rejected.violations());
			case Compiled(String planJson, String flowYaml) -> {
				deployments.findByDataflowIdAndActiveTrue(dataflow.id())
						.ifPresent(active -> deployments.save(active.deactivated()));
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
		push(() -> orchestrator.remove(dataflow.slug()));
	}

	boolean deployed(UUID dataflowId) {
		return deployments.findByDataflowIdAndActiveTrue(dataflowId).isPresent();
	}

	/**
	 * The Orchestrator call runs inside the freezing transaction: a failed push
	 * becomes a 502 problem detail and rolls the lifecycle change back, so the spine
	 * and Kestra never disagree about what is live.
	 */
	private void push(Runnable call) {
		try {
			call.run();
		}
		catch (RuntimeException e) {
			throw new ErrorResponseException(HttpStatus.BAD_GATEWAY,
					ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
							"The Orchestrator did not accept the change: " + e.getMessage()),
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
