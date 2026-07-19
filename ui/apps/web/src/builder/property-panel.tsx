import Alert from '@mui/material/Alert';
import Autocomplete from '@mui/material/Autocomplete';
import Divider from '@mui/material/Divider';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import type { Engine, ExecutionModel, SemanticViolation } from 'dataflow-config';
import { clientsKey, listClients } from '../api/clients';
import type {
  BuilderNode,
  ClientFilterPayload,
  DataflowSettings,
  NodePayload,
} from './graph';
import { useCatalogEntry } from './use-catalog-entry';

/**
 * The property panel: settings for the selected node, or — with nothing
 * selected — the Dataflow-level settings (Schedule, then the visually
 * separated Operator settings, documenting the user/operator role boundary).
 * The deploy-readiness block at the bottom is the courtesy validation's
 * fix-it list, phrased in glossary language.
 */

interface PropertyPanelProps {
  selected: BuilderNode | undefined;
  settings: DataflowSettings;
  onSettingsChange: (settings: DataflowSettings) => void;
  onPayloadChange: (nodeId: string, payload: NodePayload) => void;
  violations: SemanticViolation[];
  dirty: boolean;
}

export function PropertyPanel({
  selected,
  settings,
  onSettingsChange,
  onPayloadChange,
  violations,
  dirty,
}: PropertyPanelProps) {
  return (
    <Stack spacing={2} sx={{ width: 290, flexShrink: 0, overflowY: 'auto' }}>
      {selected === undefined ? (
        <DataflowSettingsPanel settings={settings} onChange={onSettingsChange} />
      ) : (
        <NodeSettings node={selected} onPayloadChange={onPayloadChange} />
      )}
      <Divider />
      <DeployReadiness violations={violations} dirty={dirty} />
    </Stack>
  );
}

function NodeSettings({
  node,
  onPayloadChange,
}: {
  node: BuilderNode;
  onPayloadChange: (nodeId: string, payload: NodePayload) => void;
}) {
  switch (node.type) {
    case 'source':
      return <CatalogEntryInfo role="source" entryId={node.data.payload.sourceId} />;
    case 'transform':
      return (
        <ClientFilterSettings
          payload={node.data.payload}
          onChange={(payload) => onPayloadChange(node.id, payload)}
        />
      );
    case 'destination':
      return (
        <CatalogEntryInfo role="destination" entryId={node.data.payload.destinationId} />
      );
  }
}

/** Sources and Destinations are Catalog entries — read-only in the UI. */
function CatalogEntryInfo({ role, entryId }: { role: 'source' | 'destination'; entryId: string }) {
  const entry = useCatalogEntry(role, entryId);

  return (
    <Stack spacing={1}>
      <Typography variant="overline" color="text.secondary">
        {role === 'source' ? 'Source' : 'Destination'}
      </Typography>
      <Typography variant="subtitle1">{entry?.name ?? entryId}</Typography>
      <Typography variant="body2" color="text.secondary">
        {entry?.description}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        Catalog entries are curated by admins — nothing to configure here.
      </Typography>
    </Stack>
  );
}

function ClientFilterSettings({
  payload,
  onChange,
}: {
  payload: ClientFilterPayload;
  onChange: (payload: ClientFilterPayload) => void;
}) {
  const clients = useQuery({ queryKey: clientsKey, queryFn: listClients });
  // Resolved in clientIds order so an untouched selection round-trips the
  // saved config byte-for-byte (the dirty flag must not lie).
  const value = payload.clientIds
    .map((id) => clients.data?.find((client) => client.id === id))
    .filter((client) => client !== undefined);

  return (
    <Stack spacing={1.5}>
      <Typography variant="overline" color="text.secondary">
        Filter by Clients
      </Typography>
      <Autocomplete
        multiple
        disableCloseOnSelect
        size="small"
        options={clients.data ?? []}
        value={value}
        getOptionLabel={(client) => client.name}
        isOptionEqualToValue={(option, chosen) => option.id === chosen.id}
        onChange={(_, selected) =>
          onChange({ ...payload, clientIds: selected.map((client) => client.id) })
        }
        renderInput={(params) => (
          <TextField {...params} label="Clients" placeholder="Choose Clients" />
        )}
      />
      <Typography variant="caption" color="text.secondary">
        Only rows belonging to the chosen Clients pass this Transform.
      </Typography>
    </Stack>
  );
}

const TIMEZONES = Intl.supportedValuesOf('timeZone');

function DataflowSettingsPanel({
  settings,
  onChange,
}: {
  settings: DataflowSettings;
  onChange: (settings: DataflowSettings) => void;
}) {
  const { schedule } = settings;

  return (
    <Stack spacing={2}>
      <Typography variant="overline" color="text.secondary">
        Schedule
      </Typography>
      <TextField
        select
        size="small"
        label="Runs"
        value={schedule === null ? 'manual' : 'daily'}
        onChange={(event) =>
          onChange({
            ...settings,
            schedule:
              event.target.value === 'manual'
                ? null
                : {
                    kind: 'daily',
                    time: '06:00',
                    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                  },
          })
        }
      >
        <MenuItem value="manual">Manually only</MenuItem>
        <MenuItem value="daily">Daily</MenuItem>
      </TextField>
      {schedule !== null && (
        <>
          <TextField
            size="small"
            label="Time"
            type="time"
            value={schedule.time}
            onChange={(event) =>
              onChange({ ...settings, schedule: { ...schedule, time: event.target.value } })
            }
          />
          <Autocomplete
            size="small"
            options={TIMEZONES}
            value={schedule.timezone}
            disableClearable
            onChange={(_, timezone) =>
              onChange({ ...settings, schedule: { ...schedule, timezone } })
            }
            renderInput={(params) => <TextField {...params} label="Timezone" />}
          />
        </>
      )}
      <Divider />
      <Stack spacing={2}>
        <Typography variant="overline" color="text.secondary">
          Operator settings
        </Typography>
        <TextField
          select
          size="small"
          label="Engine"
          value={settings.engine ?? ''}
          onChange={(event) =>
            onChange({ ...settings, engine: (event.target.value || null) as Engine | null })
          }
        >
          <MenuItem value="">Not set</MenuItem>
          <MenuItem value="hop">Hop</MenuItem>
          <MenuItem value="nifi">NiFi</MenuItem>
        </TextField>
        <TextField
          select
          size="small"
          label="Execution Model"
          value={settings.executionModel ?? ''}
          onChange={(event) =>
            onChange({
              ...settings,
              executionModel: (event.target.value || null) as ExecutionModel | null,
            })
          }
        >
          <MenuItem value="">Not set</MenuItem>
          <MenuItem value="batch">Batch</MenuItem>
          <MenuItem value="server">Server</MenuItem>
        </TextField>
      </Stack>
    </Stack>
  );
}

function DeployReadiness({
  violations,
  dirty,
}: {
  violations: SemanticViolation[];
  dirty: boolean;
}) {
  if (violations.length === 0 && !dirty) {
    return <Alert severity="success">Ready to deploy.</Alert>;
  }

  return (
    <Stack spacing={1}>
      {dirty && <Alert severity="info">Save your changes before deploying.</Alert>}
      {violations.length > 0 && (
        <>
          <Typography variant="overline" color="text.secondary">
            Before this can deploy
          </Typography>
          <List dense disablePadding>
            {violations.map((violation, index) => (
              <ListItem key={`${violation.rule}-${violation.nodeId ?? index}`} disableGutters>
                <ListItemText
                  primary={violation.message}
                  secondary={violation.nodeId}
                  slotProps={{ primary: { variant: 'body2' } }}
                />
              </ListItem>
            ))}
          </List>
        </>
      )}
    </Stack>
  );
}
