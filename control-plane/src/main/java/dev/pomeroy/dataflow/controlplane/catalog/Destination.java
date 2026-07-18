package dev.pomeroy.dataflow.controlplane.catalog;

/**
 * A Catalog entry representing a place files can be delivered. Users pick it by name;
 * connection details live in the physical definition, exposed only to the compiler.
 */
public record Destination(String id, String name, String description, DestinationPhysical physical) {
}
