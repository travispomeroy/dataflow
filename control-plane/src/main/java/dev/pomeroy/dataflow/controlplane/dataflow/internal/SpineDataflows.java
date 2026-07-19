package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.Dataflows;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@link Dataflows} read seam served straight off the relational spine; deployed
 * means an active Deployment exists — the one whose frozen plan is live in Kestra.
 */
@Component
class SpineDataflows implements Dataflows {

	private final DataflowRepository dataflows;

	private final DeploymentRepository deployments;

	SpineDataflows(DataflowRepository dataflows, DeploymentRepository deployments) {
		this.dataflows = dataflows;
		this.deployments = deployments;
	}

	@Override
	public Optional<DataflowRef> find(UUID id) {
		return dataflows.findById(id).map(this::ref);
	}

	@Override
	public Optional<DataflowRef> findBySlug(String slug) {
		return dataflows.findBySlug(slug).map(this::ref);
	}

	private DataflowRef ref(DataflowEntity dataflow) {
		return deployments.findByDataflowIdAndActiveTrue(dataflow.id())
				.map(active -> new DataflowRef(dataflow.id(), dataflow.slug(), true,
						businessDateTimezone(active)))
				.orElseGet(() -> new DataflowRef(dataflow.id(), dataflow.slug(), false, "UTC"));
	}

	/**
	 * The frozen config's Schedule timezone — the one the deployed flow's run-date
	 * default resolves Business Date in (#25) — UTC when manual-only.
	 */
	private String businessDateTimezone(DeploymentEntity active) {
		return active.config().schedule() != null ? active.config().schedule().timezone() : "UTC";
	}
}
