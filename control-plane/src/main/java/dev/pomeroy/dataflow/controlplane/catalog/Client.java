package dev.pomeroy.dataflow.controlplane.catalog;

/**
 * Client reference data: the account holders a Dataflow can be filtered by. The
 * upstream APIs say "investor"; the platform renames to Client at the Source boundary,
 * keeping the upstream internal id.
 */
public record Client(String id, String name, String advisorGroup) {
}
