-- Deployments join the spine (spec #10, issue #16): append-only immutable snapshots —
-- the frozen config copy and Execution Plan (ADR-0002) as jsonb documents, real
-- columns only for identity and lookup. Versions are strictly increasing per
-- Dataflow; at most one Deployment is active (deployed to Kestra) at a time.
-- Cascade on delete: delete is rejected by the API while deployed, and a hard
-- delete of an undeployed Dataflow takes its history with it (soft delete is a
-- productionization note).
CREATE TABLE deployment (
    id          uuid PRIMARY KEY,
    dataflow_id uuid NOT NULL REFERENCES dataflow (id) ON DELETE CASCADE,
    version     integer NOT NULL,
    config      jsonb NOT NULL,
    plan        jsonb NOT NULL,
    deployed_at timestamptz NOT NULL,
    active      boolean NOT NULL,
    UNIQUE (dataflow_id, version)
);

CREATE UNIQUE INDEX deployment_one_active_per_dataflow
    ON deployment (dataflow_id) WHERE active;
