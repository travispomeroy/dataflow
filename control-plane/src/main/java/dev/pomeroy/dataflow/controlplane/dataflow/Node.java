package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * A node of the Dataflow Config DAG (ADR-0005). The {@code type} discriminator is a
 * real property of every node — the same shape the mirrored TS types express as a
 * discriminated union (M1.3). {@link DataflowConfigModule} resolves it (and
 * {@code kind} for transforms) on read; serialization is plain record serialization.
 */
public sealed interface Node permits SourceNode, TransformNode, DestinationNode {

	String id();

	String type();
}
