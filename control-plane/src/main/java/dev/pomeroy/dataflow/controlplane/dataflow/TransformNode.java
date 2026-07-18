package dev.pomeroy.dataflow.controlplane.dataflow;

/**
 * A Transform node: a user-visible operation applied to data flowing through the
 * Dataflow. The {@code kind} discriminator selects the concrete transform;
 * {@code clientFilter} is the only M1 kind (Business Rules M6, Aggregate/Join M7).
 */
public sealed interface TransformNode extends Node permits ClientFilterNode {

	String kind();
}
