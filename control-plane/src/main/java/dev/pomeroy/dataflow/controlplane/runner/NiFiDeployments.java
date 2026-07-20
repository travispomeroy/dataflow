package dev.pomeroy.dataflow.controlplane.runner;

/**
 * The {@code nifi × server} Runner's deploy-time half (M4.4): converge NiFi to
 * exactly the active Deployment. {@code put} wholly replaces the Dataflow's process
 * group (name = slug, Deployment version recorded in its comments) and parameter
 * context (name = slug, the artifact's parameter contract with empty values — runs
 * late-bind the real ones), then leaves the group ready: context bound, everything
 * stopped, seeds DISABLED as minted, controller services DISABLED as uploaded (the
 * run driver enables them once the parameters hold real values).
 *
 * <p>Run-time work never lives here — since M1 the world runs with the control plane
 * stopped, so the compiled Kestra flow's embedded driver speaks to NiFi by itself.
 */
public interface NiFiDeployments {

	void put(String slug, int deploymentVersion, String flowDefinitionJson);
}
