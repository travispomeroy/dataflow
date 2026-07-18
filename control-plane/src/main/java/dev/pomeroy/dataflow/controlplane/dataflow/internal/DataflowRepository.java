package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface DataflowRepository extends ListCrudRepository<DataflowEntity, UUID> {

	Optional<DataflowEntity> findBySlug(String slug);
}
