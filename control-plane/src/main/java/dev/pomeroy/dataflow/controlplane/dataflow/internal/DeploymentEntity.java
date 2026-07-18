package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One immutable Deployment on the spine: the frozen config copy and Execution Plan
 * snapshot (ADR-0002) as jsonb documents. Rows are append-only — the only mutation a
 * Deployment ever sees is {@link #deactivated()} when it stops being the one live in
 * Kestra; the snapshot itself is retained forever.
 */
@Table("deployment")
record DeploymentEntity(@Id UUID id, UUID dataflowId, int version, DataflowConfig config,
		PlanSnapshot plan, Instant deployedAt, boolean active) {

	DeploymentEntity deactivated() {
		return new DeploymentEntity(id, dataflowId, version, config, plan, deployedAt, false);
	}
}
