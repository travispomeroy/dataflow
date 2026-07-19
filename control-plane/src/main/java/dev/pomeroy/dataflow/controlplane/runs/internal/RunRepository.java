package dev.pomeroy.dataflow.controlplane.runs.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface RunRepository extends ListCrudRepository<RunEntity, UUID> {

	/**
	 * Newest first; the id tie-break carries no meaning (ids are random) but keeps the
	 * order — and with it the list summary's lastRun, which sorts the same way in SQL —
	 * deterministic when two Runs share a start instant.
	 */
	List<RunEntity> findByDataflowIdOrderByStartedAtDescIdDesc(UUID dataflowId);

	Optional<RunEntity> findByKestraExecutionId(String kestraExecutionId);

	Optional<RunEntity> findByIdAndDataflowId(UUID id, UUID dataflowId);
}
