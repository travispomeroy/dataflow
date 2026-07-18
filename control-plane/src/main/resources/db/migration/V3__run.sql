-- Runs join the spine (spec #10, issue #17): one row per Kestra execution, keyed by
-- the execution ID so run-now's eager insert and the namespace poller's upsert are
-- one idempotent path. Real columns for status and timing (the four-state model,
-- raw Kestra state verbatim in detail); delivered files are a JSON document that
-- stays an empty array until M2 records names and record counts. It is text, not
-- jsonb, while it has no content to query: M2 owns filling it and can promote the
-- column with one USING cast when it gives the document a real shape. Cascade on
-- delete for the same reason deployment does: delete is rejected while deployed,
-- and a hard delete takes history with it.
CREATE TABLE run (
    id                  uuid PRIMARY KEY,
    dataflow_id         uuid NOT NULL REFERENCES dataflow (id) ON DELETE CASCADE,
    kestra_execution_id text NOT NULL UNIQUE,
    status              text NOT NULL,
    detail              text NOT NULL,
    started_at          timestamptz NOT NULL,
    ended_at            timestamptz,
    delivered_files     text NOT NULL
);

CREATE INDEX run_by_dataflow ON run (dataflow_id);
