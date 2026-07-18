/**
 * Hand-written TS mirror of the Dataflow Config schema — the persisted, purely
 * logical representation of a Dataflow (ADR-0005). The source of truth is the Java
 * record set in the control plane's `dataflow` module; the shared canonical examples
 * in `e2e/canonical/` hold both sides honest (`checks/canonical-examples.ts` here,
 * `DataflowConfigContractTests` there). Change one side and the build tells you to
 * change the other. No runtime validation, no codegen — types only.
 *
 * The types describe the canonical document form the control plane emits: `nodes` and
 * `edges` are always arrays, and the optional trio (`schedule`, `engine`,
 * `executionModel`) is always present, explicitly `null` when unset.
 */

/**
 * A node reading a Catalog Source. `sourceId` references the Catalog; it must
 * resolve at save time (structural validation).
 */
export interface SourceNode {
	id: string;
	type: 'source';
	sourceId: string;
}

/**
 * The "filter by Clients" Transform: only rows belonging to the chosen Clients pass.
 * Client ids reference the clients reference data; they must resolve at save time.
 */
export interface ClientFilterNode {
	id: string;
	type: 'transform';
	kind: 'clientFilter';
	clientIds: string[];
}

/**
 * A Transform node: a user-visible operation applied to data flowing through the
 * Dataflow. The `kind` discriminator selects the concrete transform; `clientFilter`
 * is the only M1 kind (Business Rules M6, Aggregate/Join M7).
 */
export type TransformNode = ClientFilterNode;

/**
 * A node delivering to a Catalog Destination. `destinationId` references the Catalog;
 * it must resolve at save time (structural validation).
 */
export interface DestinationNode {
	id: string;
	type: 'destination';
	destinationId: string;
}

/**
 * A node of the Dataflow Config DAG, discriminated by `type` (and `kind` within
 * transforms) — the union shape the Java side resolves with a deserializer.
 */
export type Node = SourceNode | TransformNode | DestinationNode;

/**
 * A directed edge of the DAG: data flows `from` one node id `to` another. Both ends
 * must reference existing nodes (structural validation).
 */
export interface Edge {
	from: string;
	to: string;
}

/**
 * The kind of Schedule. `daily` ("at a time in a timezone, every day") is the only
 * POC kind; business-day and holiday calendars are productionization notes.
 */
export type ScheduleKind = 'daily';

/**
 * When the Dataflow runs automatically — a first-class concept, never a bare cron
 * string (cron is a Kestra-compiler detail). `time` is a 24h `HH:mm` wall-clock time
 * in the named `timezone`. A Dataflow with no Schedule is manual-only.
 */
export interface Schedule {
	kind: ScheduleKind;
	time: string;
	timezone: string;
}

/**
 * The pluggable ETL technology performing this Dataflow's extract/transform work.
 * Operator-set, orthogonal to {@link ExecutionModel} (ADR-0003); invisible to end
 * users.
 */
export type Engine = 'hop' | 'nifi';

/**
 * How the Engine executes a Run: `server` (long-running engine, runs triggered
 * against it) or `batch` (ephemeral run-to-completion). Operator-set, orthogonal to
 * {@link Engine} (ADR-0003).
 */
export type ExecutionModel = 'server' | 'batch';

/**
 * The persisted, purely logical representation of a Dataflow: a DAG of nodes and
 * edges, an optional {@link Schedule} (`null` = manual-only), and the operator-set
 * {@link Engine} / {@link ExecutionModel} axes. Never contains physical details —
 * those live behind the Catalog.
 *
 * Any structurally well-formed document is a saveable Draft, however half-built;
 * semantic rules (connected, acyclic, linear-until-M7) belong to Deploy, not here.
 */
export interface DataflowConfig {
	nodes: Node[];
	edges: Edge[];
	schedule: Schedule | null;
	engine: Engine | null;
	executionModel: ExecutionModel | null;
}
