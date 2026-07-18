/**
 * The contract check that keeps the TS mirror honest (M1.3): the canonical config
 * examples in e2e/canonical/ are the shared contract — the Java records round-trip
 * them (DataflowConfigContractTests) and this file makes tsc type-check them against
 * the mirror types. Drift in either direction breaks a build.
 *
 * tsc imports JSON with every string widened to `string` (microsoft/TypeScript#32063),
 * so the imported file cannot be checked against the discriminated union directly —
 * `type: string` never satisfies `type: 'source'`. The bridge, in three steps with
 * teeth at each:
 *
 * 1. `positionsFeed` restates the canonical example as a fresh object literal.
 *    `satisfies DataflowConfig` checks it with full strength: literal discriminators,
 *    excess-property checks, required fields.
 * 2. Assigning the imported JSON to `Widen<typeof positionsFeed>` proves every key and
 *    structure the literal has, the file has (a field renamed or removed in the JSON
 *    fails here).
 * 3. Assigning back to `typeof positionsFeedJson` proves the reverse (a field added to
 *    the JSON — e.g. the Java schema grew and the example was updated — fails here
 *    until the mirror and this literal learn it too).
 */
import type { DataflowConfig } from '../src/index';
import positionsFeedJson from '../../../../e2e/canonical/positions-feed.config.json';

/**
 * Recursively widens literal string types to `string` — the same widening tsc applies
 * to JSON imports — so a literal-typed restatement becomes shape-comparable with the
 * imported file.
 */
type Widen<T> = T extends string
	? string
	: T extends readonly (infer E)[]
		? Widen<E>[]
		: T extends object
			? { [K in keyof T]: Widen<T[K]> }
			: T;

// Step 1 — the canonical Positions Feed example, restated. Steps 2 and 3 keep its
// shape (keys and structure) identical to e2e/canonical/positions-feed.config.json;
// widened values can drift without failing here — value fidelity is the Java
// round-trip test's job.
export const positionsFeed = {
	nodes: [
		{
			id: 'positions-source',
			type: 'source',
			sourceId: 'positions',
		},
		{
			id: 'client-filter',
			type: 'transform',
			kind: 'clientFilter',
			clientIds: ['INV-001', 'INV-002', 'INV-003'],
		},
		{
			id: 'pomeroy-delivery',
			type: 'destination',
			destinationId: 'pomeroy-provider',
		},
	],
	edges: [
		{ from: 'positions-source', to: 'client-filter' },
		{ from: 'client-filter', to: 'pomeroy-delivery' },
	],
	schedule: {
		kind: 'daily',
		time: '06:30',
		timezone: 'America/New_York',
	},
	engine: 'hop',
	executionModel: 'batch',
} satisfies DataflowConfig;

// Step 2 — everything the literal has, the file has.
export const fileHasTheCanonicalShape: Widen<typeof positionsFeed> = positionsFeedJson;

// Step 3 — everything the file has, the literal (and so the mirror) has.
declare const widenedPositionsFeed: Widen<typeof positionsFeed>;
export const fileHasNothingMore: typeof positionsFeedJson = widenedPositionsFeed;

// A manual-only, half-built Draft is a valid document: explicit nulls for Schedule and
// the operator fields, no edges yet. Mirrors the Java contract test's third case.
export const halfBuiltManualOnlyDraft = {
	nodes: [{ id: 'positions-source', type: 'source', sourceId: 'positions' }],
	edges: [],
	schedule: null,
	engine: null,
	executionModel: null,
} satisfies DataflowConfig;
