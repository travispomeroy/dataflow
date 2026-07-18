package dev.pomeroy.dataflow.controlplane.runs.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface RunRepository extends ListCrudRepository<RunEntity, UUID> {

	List<RunEntity> findByDataflowIdOrderByStartedAtDesc(UUID dataflowId);

	Optional<RunEntity> findByKestraExecutionId(String kestraExecutionId);

	Optional<RunEntity> findByIdAndDataflowId(UUID id, UUID dataflowId);
}
