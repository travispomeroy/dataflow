-- The thin relational spine (spec #10): jsonb documents, real columns only for
-- identity and lookup. The deployment and run tables join the spine with their
-- own migrations when M1.6/M1.7 land.
CREATE TABLE dataflow (
    id     uuid PRIMARY KEY,
    -- Minted from the initial name, immutable for life (it becomes the Kestra
    -- flow ID in M1.6); rename changes display name only.
    slug   text NOT NULL UNIQUE,
    name   text NOT NULL,
    -- The mutable Draft Dataflow Config, exactly as the schema records serialize.
    config jsonb NOT NULL
);
