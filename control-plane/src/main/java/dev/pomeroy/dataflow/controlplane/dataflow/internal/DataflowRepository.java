package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface DataflowRepository extends ListCrudRepository<DataflowEntity, UUID> {

	Optional<DataflowEntity> findBySlug(String slug);

	/**
	 * The card-ready list projection (issue #29), one query for every Dataflow — never
	 * per-dataflow lookups. Drift is jsonb inequality computed where the documents
	 * live: Postgres compares jsonb semantically (key order and whitespace never
	 * matter), which is exactly the canonical-JSON comparison of the Draft against the
	 * active Deployment's frozen config copy — and false when undeployed, by
	 * construction of the join. The lateral picks each Dataflow's latest Run by the
	 * same ordering the run-history API serves — started-at, id as the shared
	 * tie-break — so the summary's lastRun is always the history's first entry. The
	 * {@code run} table belongs to the
	 * runs module, which depends on this one — reading it here in SQL is the projection
	 * seam that avoids a module cycle.
	 */
	@Query(value = """
			SELECT df.id, df.slug, df.name,
			       dep.version AS active_deployment_version,
			       (dep.id IS NOT NULL AND df.config IS DISTINCT FROM dep.config) AS draft_drifted,
			       last_run.status AS last_run_status,
			       last_run.started_at AS last_run_started_at,
			       last_run.ended_at AS last_run_ended_at,
			       last_run.business_date AS last_run_business_date
			FROM dataflow df
			LEFT JOIN deployment dep ON dep.dataflow_id = df.id AND dep.active
			LEFT JOIN LATERAL (
			    SELECT run.status, run.started_at, run.ended_at, run.business_date
			    FROM run
			    WHERE run.dataflow_id = df.id
			    ORDER BY run.started_at DESC, run.id DESC
			    LIMIT 1
			) last_run ON true
			ORDER BY df.slug
			""", rowMapperClass = DataflowSummary.Mapper.class)
	List<DataflowSummary> summaries();
}
