/**
 * Hand-written TS mirror of the control plane's deploy-time semantic rule set
 * (`SemanticValidator`, ADR-0005) — the canvas's courtesy pre-check (M3.2). The rule
 * ids are the contract, shared with the Java side and held honest by the annotated
 * violation examples in `e2e/canonical/violations/` (`semantic-validation.spec.ts`
 * here, `SemanticValidationExampleContractTests` there). The control plane remains the
 * only authority; this runs synchronously per graph edit so Deploy can be gated with
 * plain-language reasons instead of a raw 422.
 *
 * All rules run — the violations list is the builder's complete fix-it list, not the
 * first failure. Assumes a structurally valid document (save-time validation): edges
 * whose ends don't resolve to nodes are ignored rather than crashing the rule set.
 */
import type { DataflowConfig, Node } from './dataflow-config';

/**
 * The stable rule identifiers, one per failure mode — the exact set
 * `SemanticViolation` (Java) documents.
 */
export type SemanticRuleId =
	| 'delivery-count'
	| 'disconnected'
	| 'cycle'
	| 'non-linear'
	| 'headless-path'
	| 'dangling-path'
	| 'source-mid-path'
	| 'delivery-mid-path'
	| 'missing-operator-fields';

/**
 * One entry of the courtesy violations list. `message` is the user-facing text for
 * the builder's list; `nodeId` names the offending node for node-scoped rules so the
 * canvas can point at it.
 */
export interface SemanticViolation {
	rule: SemanticRuleId;
	message: string;
	nodeId?: string;
}

/**
 * The user-facing message per rule id, in glossary language (Source, Transform,
 * Destination, Delivery, Dataflow — never node, vertex, DAG).
 */
export const SEMANTIC_RULE_MESSAGES: Record<SemanticRuleId, string> = {
	'delivery-count': 'a Dataflow delivers to exactly one Destination',
	disconnected: 'everything on the canvas must connect into one Dataflow',
	cycle: 'data cannot flow in a circle — remove the loop',
	'non-linear': 'only linear Dataflows can deploy for now — no branching or converging yet',
	'headless-path': 'this path does not start at a Source',
	'dangling-path': 'this branch does not reach the Delivery',
	'source-mid-path': 'a Source starts a path — nothing can flow into it',
	'delivery-mid-path': 'every path ends at the Delivery — nothing can flow out of it',
	'missing-operator-fields':
		'Engine and Execution Model must be set (Operator settings) before deploying',
};

/**
 * The courtesy pre-check: the same judgements, in the same order, as the Java
 * `SemanticValidator`. Pure and synchronous — call it on every graph edit.
 */
export function validate(config: DataflowConfig): SemanticViolation[] {
	const violations: SemanticViolation[] = [];
	const graph = new Graph(config);

	const deliveries = config.nodes.filter((node) => node.type === 'destination').length;
	if (deliveries !== 1) {
		violations.push(violation('delivery-count'));
	}

	if (!graph.connected()) {
		violations.push(violation('disconnected'));
	}

	if (graph.cyclic()) {
		violations.push(violation('cycle'));
	}

	for (const node of config.nodes) {
		if (graph.outDegree(node) > 1 || graph.inDegree(node) > 1) {
			violations.push(violation('non-linear', node));
		}
		if (graph.inDegree(node) === 0 && node.type !== 'source') {
			violations.push(violation('headless-path', node));
		}
		if (graph.outDegree(node) === 0 && node.type !== 'destination') {
			violations.push(violation('dangling-path', node));
		}
		if (node.type === 'source' && graph.inDegree(node) > 0) {
			violations.push(violation('source-mid-path', node));
		}
		if (node.type === 'destination' && graph.outDegree(node) > 0) {
			violations.push(violation('delivery-mid-path', node));
		}
	}

	// == null, not ===: tolerate undefined the way the Java validator's null check does.
	if (config.engine == null || config.executionModel == null) {
		violations.push(violation('missing-operator-fields'));
	}

	return violations;
}

function violation(rule: SemanticRuleId, node?: Node): SemanticViolation {
	return {
		rule,
		message: SEMANTIC_RULE_MESSAGES[rule],
		...(node === undefined ? {} : { nodeId: node.id }),
	};
}

/** The config graph with unresolvable edges dropped, plus the shape queries. */
class Graph {
	private readonly nodes: Node[];

	private readonly outgoing = new Map<string, string[]>();

	private readonly incoming = new Map<string, string[]>();

	constructor(config: DataflowConfig) {
		this.nodes = config.nodes;
		for (const node of this.nodes) {
			this.outgoing.set(node.id, []);
			this.incoming.set(node.id, []);
		}
		for (const edge of config.edges) {
			const out = this.outgoing.get(edge.from);
			const into = this.incoming.get(edge.to);
			if (out !== undefined && into !== undefined) {
				out.push(edge.to);
				into.push(edge.from);
			}
		}
	}

	outDegree(node: Node): number {
		return this.outgoing.get(node.id)?.length ?? 0;
	}

	inDegree(node: Node): number {
		return this.incoming.get(node.id)?.length ?? 0;
	}

	connected(): boolean {
		if (this.nodes.length <= 1) {
			return true;
		}
		const reached = new Set<string>();
		const frontier = [this.nodes[0].id];
		while (frontier.length > 0) {
			const id = frontier.pop() as string;
			if (!reached.has(id)) {
				reached.add(id);
				frontier.push(...(this.outgoing.get(id) ?? []), ...(this.incoming.get(id) ?? []));
			}
		}
		return reached.size === this.nodes.length;
	}

	cyclic(): boolean {
		// Kahn's algorithm: whatever cannot be topologically ordered is on a cycle.
		const remainingIn = new Map<string, number>();
		for (const [id, from] of this.incoming) {
			remainingIn.set(id, from.length);
		}
		const ready = [...remainingIn]
			.filter(([, degree]) => degree === 0)
			.map(([id]) => id);
		let ordered = 0;
		while (ready.length > 0) {
			const id = ready.pop() as string;
			ordered++;
			for (const next of this.outgoing.get(id) ?? []) {
				const degree = (remainingIn.get(next) ?? 0) - 1;
				remainingIn.set(next, degree);
				if (degree === 0) {
					ready.push(next);
				}
			}
		}
		return ordered < this.nodes.length;
	}
}
