package dev.pomeroy.dataflow.controlplane.catalog;

import java.util.List;

/**
 * The hidden composition behind a Source: the upstream APIs it projects over and how
 * their records merge into one logical dataset. Exposed to the compiler only — never
 * over the REST API.
 */
public record SourcePhysical(String baseUrl, Merge merge, List<UpstreamApi> apis) {
}
