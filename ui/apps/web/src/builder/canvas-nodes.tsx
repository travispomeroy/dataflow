import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import { Handle, Position } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import type {
  DestinationBuilderNode,
  SourceBuilderNode,
  TransformBuilderNode,
} from './graph';
import { useCatalogEntry } from './use-catalog-entry';

/**
 * The canvas node components, one per config node type. Handles encode the
 * glossary's shape rules visually: a Source only emits, a Delivery only
 * receives, a Transform does both. Display names come from the Catalog (the
 * config carries only ids); the id itself is the honest fallback.
 */

function NodeShell({
  role,
  name,
  selected,
}: {
  role: string;
  name: string;
  selected: boolean | undefined;
}) {
  return (
    <Paper
      elevation={selected ? 4 : 1}
      sx={{
        px: 2,
        py: 1,
        minWidth: 160,
        border: 1,
        borderColor: selected ? 'primary.main' : 'divider',
      }}
    >
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {role}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 'medium' }}>
        {name}
      </Typography>
    </Paper>
  );
}

function SourceCanvasNode({ data, selected }: NodeProps<SourceBuilderNode>) {
  const name = useCatalogEntry('source', data.payload.sourceId)?.name ?? data.payload.sourceId;

  return (
    <>
      <NodeShell role="Source" name={name} selected={selected} />
      <Handle type="source" position={Position.Right} />
    </>
  );
}

function TransformCanvasNode({ data, selected }: NodeProps<TransformBuilderNode>) {
  const count = data.payload.clientIds.length;

  return (
    <>
      <Handle type="target" position={Position.Left} />
      <NodeShell
        role="Transform"
        name={count === 0 ? 'Filter by Clients' : `Filter by Clients (${count})`}
        selected={selected}
      />
      <Handle type="source" position={Position.Right} />
    </>
  );
}

function DestinationCanvasNode({ data, selected }: NodeProps<DestinationBuilderNode>) {
  const name =
    useCatalogEntry('destination', data.payload.destinationId)?.name ??
    data.payload.destinationId;

  return (
    <>
      <Handle type="target" position={Position.Left} />
      <NodeShell role="Destination" name={name} selected={selected} />
    </>
  );
}

/** Module-level so React Flow never sees a fresh object identity per render. */
export const nodeTypes = {
  source: SourceCanvasNode,
  transform: TransformCanvasNode,
  destination: DestinationCanvasNode,
};
