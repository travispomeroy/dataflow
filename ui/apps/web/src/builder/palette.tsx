import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import type { DragEvent } from 'react';
import { catalogKeys, listDestinations, listSources } from '../api/catalog';
import type { NodePayload } from './graph';

/**
 * The palette: what can go on the canvas, grouped in glossary terms — Sources
 * and Destinations from the Catalog, Transforms a static list ("Filter by
 * Clients" is the only M3 kind; Business Rules M6, Aggregate/Join M7 slot in
 * here). Drag-to-add only; the drop handler on the canvas mints the id.
 */

/** The drag payload's MIME type — how the canvas recognizes a palette drag. */
export const PALETTE_NODE_MIME = 'application/x-dataflow-node';

function PaletteItem({
  label,
  description,
  payload,
}: {
  label: string;
  description?: string;
  payload: NodePayload;
}) {
  const onDragStart = (event: DragEvent) => {
    event.dataTransfer.setData(PALETTE_NODE_MIME, JSON.stringify(payload));
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <Paper
      variant="outlined"
      draggable
      onDragStart={onDragStart}
      aria-label={label}
      sx={{ px: 1.5, py: 1, cursor: 'grab' }}
    >
      <Typography variant="body2">{label}</Typography>
      {description && (
        <Typography variant="caption" color="text.secondary">
          {description}
        </Typography>
      )}
    </Paper>
  );
}

function PaletteGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Stack spacing={1}>
      <Typography variant="overline" color="text.secondary">
        {title}
      </Typography>
      {children}
    </Stack>
  );
}

export function Palette() {
  const sources = useQuery({ queryKey: catalogKeys.sources, queryFn: listSources });
  const destinations = useQuery({
    queryKey: catalogKeys.destinations,
    queryFn: listDestinations,
  });

  return (
    <Stack spacing={2} sx={{ width: 210, flexShrink: 0, overflowY: 'auto' }}>
      <PaletteGroup title="Sources">
        {sources.data?.map((entry) => (
          <PaletteItem
            key={entry.id}
            label={entry.name}
            description={entry.description}
            payload={{ type: 'source', sourceId: entry.id }}
          />
        ))}
      </PaletteGroup>
      <PaletteGroup title="Transforms">
        <PaletteItem
          label="Filter by Clients"
          description="Only rows belonging to the chosen Clients pass"
          payload={{ type: 'transform', kind: 'clientFilter', clientIds: [] }}
        />
      </PaletteGroup>
      <PaletteGroup title="Destinations">
        {destinations.data?.map((entry) => (
          <PaletteItem
            key={entry.id}
            label={entry.name}
            description={entry.description}
            payload={{ type: 'destination', destinationId: entry.id }}
          />
        ))}
      </PaletteGroup>
    </Stack>
  );
}
