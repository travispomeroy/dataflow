package dev.pomeroy.dataflow.controlplane.catalog;

/**
 * A Catalog entry representing a logical dataset a user can start a Dataflow from.
 * The physical composition behind it is invisible to users — it never crosses the REST
 * API; the compiler reads it through this module API.
 */
public record Source(String id, String name, String description, SourcePhysical physical) {
}
