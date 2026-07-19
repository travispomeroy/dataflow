/**
 * The builder's pure seam (issue #33): deterministic readable node ids and the
 * canvas ⇄ Dataflow Config projection. The acceptance criteria live here —
 * identical build sequences mint identical ids, collisions suffix, and the
 * round trip is lossless so the dirty flag and JSON preview never lie.
 */
import type { DataflowConfig } from 'dataflow-config';
import { describe, expect, it } from 'vitest';
import { configEquals, fromConfig, mintNodeId, toConfig } from './graph';

/** The canonical Positions Feed shape, as the control plane would serve it. */
const POSITIONS_FEED: DataflowConfig = {
  nodes: [
    { id: 'positions-source', type: 'source', sourceId: 'positions' },
    {
      id: 'client-filter',
      type: 'transform',
      kind: 'clientFilter',
      clientIds: ['INV-001', 'INV-002', 'INV-003'],
    },
    { id: 'pomeroy-provider-delivery', type: 'destination', destinationId: 'pomeroy-provider' },
  ],
  edges: [
    { from: 'positions-source', to: 'client-filter' },
    { from: 'client-filter', to: 'pomeroy-provider-delivery' },
  ],
  schedule: { kind: 'daily', time: '06:30', timezone: 'America/New_York' },
  engine: 'hop',
  executionModel: 'batch',
};

describe('mintNodeId', () => {
  it('names a node after what it is, in glossary terms', () => {
    expect(mintNodeId({ type: 'source', sourceId: 'positions' }, [])).toBe('positions-source');
    expect(mintNodeId({ type: 'transform', kind: 'clientFilter', clientIds: [] }, [])).toBe(
      'client-filter',
    );
    expect(
      mintNodeId({ type: 'destination', destinationId: 'pomeroy-provider' }, []),
    ).toBe('pomeroy-provider-delivery');
  });

  it('suffixes -2, then -3, on collision', () => {
    const payload = { type: 'source', sourceId: 'positions' } as const;

    expect(mintNodeId(payload, ['positions-source'])).toBe('positions-source-2');
    expect(mintNodeId(payload, ['positions-source', 'positions-source-2'])).toBe(
      'positions-source-3',
    );
  });

  it('is deterministic across identical build sequences', () => {
    const build = () => {
      const ids: string[] = [];
      ids.push(mintNodeId({ type: 'source', sourceId: 'positions' }, ids));
      ids.push(mintNodeId({ type: 'transform', kind: 'clientFilter', clientIds: [] }, ids));
      ids.push(mintNodeId({ type: 'transform', kind: 'clientFilter', clientIds: [] }, ids));
      ids.push(mintNodeId({ type: 'destination', destinationId: 'pomeroy-provider' }, ids));
      return ids;
    };

    expect(build()).toEqual(build());
    expect(build()).toEqual([
      'positions-source',
      'client-filter',
      'client-filter-2',
      'pomeroy-provider-delivery',
    ]);
  });
});

describe('canvas ⇄ config projection', () => {
  it('round-trips a persisted Draft losslessly, so loading is never dirty', () => {
    const { nodes, edges, settings } = fromConfig(POSITIONS_FEED);

    expect(configEquals(toConfig(nodes, edges, settings), POSITIONS_FEED)).toBe(true);
  });

  it('round-trips the blank canvas', () => {
    const empty: DataflowConfig = {
      nodes: [],
      edges: [],
      schedule: null,
      engine: null,
      executionModel: null,
    };
    const { nodes, edges, settings } = fromConfig(empty);

    expect(configEquals(toConfig(nodes, edges, settings), empty)).toBe(true);
  });

  it('lays canvas nodes out at deterministic positions', () => {
    expect(fromConfig(POSITIONS_FEED).nodes.map((node) => node.position)).toEqual(
      fromConfig(POSITIONS_FEED).nodes.map((node) => node.position),
    );
  });

  it('projects canvas edges as from/to pairs in draw order', () => {
    const { nodes, edges, settings } = fromConfig(POSITIONS_FEED);

    expect(toConfig(nodes, edges, settings).edges).toEqual(POSITIONS_FEED.edges);
  });
});

describe('configEquals', () => {
  it('ignores key order but not node order', () => {
    const reordered = JSON.parse(
      JSON.stringify({ ...POSITIONS_FEED, extra: undefined }),
    ) as DataflowConfig;
    reordered.nodes = [...reordered.nodes].reverse();

    expect(
      configEquals(POSITIONS_FEED, {
        engine: 'hop',
        executionModel: 'batch',
        schedule: { timezone: 'America/New_York', time: '06:30', kind: 'daily' },
        edges: POSITIONS_FEED.edges,
        nodes: POSITIONS_FEED.nodes,
      }),
    ).toBe(true);
    expect(configEquals(POSITIONS_FEED, reordered)).toBe(false);
  });

  it('detects a single changed field', () => {
    expect(
      configEquals(POSITIONS_FEED, {
        ...POSITIONS_FEED,
        schedule: { kind: 'daily', time: '06:31', timezone: 'America/New_York' },
      }),
    ).toBe(false);
  });
});
