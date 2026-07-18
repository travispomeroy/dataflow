package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface DataflowRepository extends ListCrudRepository<DataflowEntity, UUID> {
}
