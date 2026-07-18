package dev.pomeroy.dataflow.controlplane.catalog;

import java.util.List;

/**
 * The Catalog module's public API: the curated, read-only registry of what Dataflows
 * are built from. Backed by committed seed resources loaded in-memory at startup —
 * every catalog change is a reviewable git diff (spec #10).
 */
public interface Catalog {

	List<Source> sources();

	List<Destination> destinations();

	List<FileDefinition> fileDefinitions();

	List<Client> clients();
}
