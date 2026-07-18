package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The dataflow row of the relational spine: identity columns plus the Draft config as
 * one jsonb document. The id is server-generated ({@link DataflowPersistenceConfiguration});
 * the slug is minted once at creation and never changes.
 */
@Table("dataflow")
record DataflowEntity(@Id UUID id, String slug, String name, DataflowConfig config) {
}
