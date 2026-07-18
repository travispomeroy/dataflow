package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * One resolved transform step of the plan, in execution order. The {@code kind}
 * discriminator is a real property of every step, mirroring the config's transform
 * nodes; {@code clientFilter} is the only M1 kind.
 */
public sealed interface TransformStep permits ClientFilterStep {

	String kind();
}
