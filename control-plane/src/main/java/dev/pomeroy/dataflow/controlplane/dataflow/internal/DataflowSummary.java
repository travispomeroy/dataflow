package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * One card of the Dataflow list (issue #29): identity, the deployment fact ("Deployed
 * vN" is {@code activeDeploymentVersion}, null when undeployed), whether the Draft has
 * drifted ahead of the active Deployment's frozen config, and the latest Run — or null
 * when none has ever run. The queried row is exactly the API document, the same
 * convention the jsonb columns follow.
 *
 * <p>{@code lastRun.status} is the four-state Run status as text: the runs module owns
 * the {@link dev.pomeroy.dataflow.controlplane.runs.RunStatus} enum and already depends
 * on this module, so the summary carries the status verbatim rather than closing a
 * module cycle over it.
 */
record DataflowSummary(UUID id, String slug, String name, Integer activeDeploymentVersion,
		boolean draftDrifted, LastRun lastRun) {

	record LastRun(String status, Instant startedAt, Instant endedAt, LocalDate businessDate) {
	}

	/** {@code status} is NOT NULL on the run row — its absence means no Run exists. */
	static class Mapper implements RowMapper<DataflowSummary> {

		@Override
		public DataflowSummary mapRow(ResultSet row, int rowNumber) throws SQLException {
			String lastRunStatus = row.getString("last_run_status");
			return new DataflowSummary(row.getObject("id", UUID.class), row.getString("slug"),
					row.getString("name"),
					row.getObject("active_deployment_version", Integer.class),
					row.getBoolean("draft_drifted"),
					lastRunStatus == null ? null
							: new LastRun(lastRunStatus, instant(row, "last_run_started_at"),
									instant(row, "last_run_ended_at"),
									row.getObject("last_run_business_date", LocalDate.class)));
		}

		private static Instant instant(ResultSet row, String column) throws SQLException {
			OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
			return value == null ? null : value.toInstant();
		}
	}
}
