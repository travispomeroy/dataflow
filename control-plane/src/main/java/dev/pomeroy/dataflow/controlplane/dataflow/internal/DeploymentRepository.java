package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface DeploymentRepository extends ListCrudRepository<DeploymentEntity, UUID> {

	List<DeploymentEntity> findByDataflowIdOrderByVersionDesc(UUID dataflowId);

	Optional<DeploymentEntity> findByDataflowIdAndActiveTrue(UUID dataflowId);

	Optional<DeploymentEntity> findByDataflowIdAndVersion(UUID dataflowId, int version);
}
