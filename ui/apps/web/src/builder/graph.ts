/**
 * The builder's pure seam: deterministic readable node ids and the canvas ⇄
 * Dataflow Config projection. No React, no React Flow runtime — types only —
 * so the acceptance-critical logic (id minting, dirty tracking, the JSON
 * preview's document) stays testable without a browser.
 */
import type { Edge as FlowEdge, Node as FlowNode, XYPosition } from '@xyflow/react';
import type {
  ClientFilterNode,
  DataflowConfig,
  DestinationNode,
  Engine,
  ExecutionModel,
  Node as ConfigNode,
  Schedule,
  SourceNode,
} from 'dataflow-config';

export type SourcePayload = Omit<SourceNode, 'id'>;
export type ClientFilterPayload = Omit<ClientFilterNode, 'id'>;
export type DestinationPayload = Omit<DestinationNode, 'id'>;

/**
 * A canvas node's config payload — a {@link ConfigNode} before it has an id.
 * The id is minted at drop time from what the node is.
 */
export type NodePayload = SourcePayload | ClientFilterPayload | DestinationPayload;

/**
 * Mints the deterministic readable id for a new node (never a UUID — the JSON
 * preview is a teaching surface): what the node is, `-2`/`-3`/… on collision.
 */
export function mintNodeId(payload: NodePayload, existing: readonly string[]): string {
  const base = baseId(payload);
  if (!existing.includes(base)) {
    return base;
  }
  let suffix = 2;
  while (existing.includes(`${base}-${suffix}`)) {
    suffix++;
  }
  return `${base}-${suffix}`;
}

function baseId(payload: NodePayload): string {
  switch (payload.type) {
    case 'source':
      return `${payload.sourceId}-source`;
    case 'transform':
      return 'client-filter';
    case 'destination':
      return `${payload.destinationId}-delivery`;
  }
}

export type SourceBuilderNode = FlowNode<{ payload: SourcePayload }, 'source'>;
export type TransformBuilderNode = FlowNode<{ payload: ClientFilterPayload }, 'transform'>;
export type DestinationBuilderNode = FlowNode<{ payload: DestinationPayload }, 'destination'>;

/**
 * A React Flow node of the builder canvas: the config `type` doubles as the
 * React Flow node type selecting the node component, and `data.payload` is
 * the node's config-document half. Everything else (position, selection) is
 * ephemeral UI state the config never sees.
 */
export type BuilderNode = SourceBuilderNode | TransformBuilderNode | DestinationBuilderNode;

/** The Dataflow-level half of the config — everything that is not the graph. */
export interface DataflowSettings {
  schedule: Schedule | null;
  engine: Engine | null;
  executionModel: ExecutionModel | null;
}

/** A canvas node for a payload. TS cannot correlate the payload union member
 * with the node-type literal it came from, hence the one contained cast. */
export function builderNode(id: string, payload: NodePayload, position: XYPosition): BuilderNode {
  return { id, type: payload.type, position, data: { payload } } as BuilderNode;
}

/** A canvas edge for a drawn connection, with the deterministic readable id. */
export function builderEdge(from: string, to: string): FlowEdge {
  return { id: `${from}->${to}`, source: from, target: to };
}

/**
 * A persisted Draft, projected onto the canvas. Positions are ephemeral UI
 * state — the config is purely logical (ADR-0005) — so nodes are laid out
 * deterministically in document order, left to right.
 */
export function fromConfig(config: DataflowConfig): {
  nodes: BuilderNode[];
  edges: FlowEdge[];
  settings: DataflowSettings;
} {
  return {
    nodes: config.nodes.map((node, index) => {
      const { id, ...payload } = node;
      return builderNode(id, payload, { x: 40 + index * 260, y: 120 });
    }),
    edges: config.edges.map((edge) => builderEdge(edge.from, edge.to)),
    settings: {
      schedule: config.schedule,
      engine: config.engine,
      executionModel: config.executionModel,
    },
  };
}

/**
 * The live in-memory Dataflow Config — what Save would persist and what the
 * JSON preview shows. Node and edge order is insertion order, so identical
 * build sequences produce identical documents.
 */
export function toConfig(
  nodes: readonly BuilderNode[],
  edges: readonly FlowEdge[],
  settings: DataflowSettings,
): DataflowConfig {
  return {
    nodes: nodes.map((node) => ({ id: node.id, ...node.data.payload }) as ConfigNode),
    edges: edges.map((edge) => ({ from: edge.source, to: edge.target })),
    schedule: settings.schedule,
    engine: settings.engine,
    executionModel: settings.executionModel,
  };
}

/**
 * Structural equality of two config documents — the dirty flag. Key order is
 * irrelevant (JSON objects), element order is not (nodes and edges are
 * sequences).
 */
export function configEquals(a: DataflowConfig, b: DataflowConfig): boolean {
  return jsonEquals(a, b);
}

function jsonEquals(a: unknown, b: unknown): boolean {
  if (a === b) {
    return true;
  }
  if (Array.isArray(a) || Array.isArray(b)) {
    return (
      Array.isArray(a) &&
      Array.isArray(b) &&
      a.length === b.length &&
      a.every((element, index) => jsonEquals(element, b[index]))
    );
  }
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) {
    return false;
  }
  const entriesA = Object.entries(a).filter(([, value]) => value !== undefined);
  const entriesB = Object.entries(b).filter(([, value]) => value !== undefined);
  return (
    entriesA.length === entriesB.length &&
    entriesA.every(([key, value]) => jsonEquals(value, (b as Record<string, unknown>)[key]))
  );
}
