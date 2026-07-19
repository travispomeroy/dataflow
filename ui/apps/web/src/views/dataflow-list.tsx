import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardActionArea from '@mui/material/CardActionArea';
import CardActions from '@mui/material/CardActions';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import {
  createDataflow,
  dataflowKeys,
  deleteDataflow,
  listDataflows,
  undeployDataflow,
} from '../api/dataflows';
import type { DataflowSummary } from '../api/dataflows';
import { relativeTime } from '../runs/relative-time';
import { RunStatusChip } from '../runs/run-status-chip';
import { ConfirmDialog } from './confirm-dialog';
import { CreateDataflowDialog } from './create-dataflow-dialog';

/** A card action awaiting its confirmation dialog. */
interface PendingAction {
  action: 'undeploy' | 'delete';
  dataflow: DataflowSummary;
}

/**
 * The status board (issue #32): one card per Dataflow from the single summary
 * request — deployment fact, drift badge, last-run chip. Refetches on
 * navigation and focus only; the run-history view is the one that polls.
 */
export function DataflowListView() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const dataflows = useQuery({ queryKey: dataflowKeys.list, queryFn: listDataflows });

  const [createOpen, setCreateOpen] = useState(false);
  const create = useMutation({
    mutationFn: createDataflow,
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: dataflowKeys.list });
      await navigate(`/dataflows/${created.id}`);
    },
  });

  const [pending, setPending] = useState<PendingAction | null>(null);
  // Invalidating the 'dataflows' prefix refreshes the cards and any cached
  // detail/deployments the builder holds for the affected Dataflow.
  const closeAndRefresh = async () => {
    setPending(null);
    await queryClient.invalidateQueries({ queryKey: dataflowKeys.list });
  };
  const undeploy = useMutation({ mutationFn: undeployDataflow, onSuccess: closeAndRefresh });
  const remove = useMutation({ mutationFn: deleteDataflow, onSuccess: closeAndRefresh });

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <Typography variant="h4" component="h1">
          Dataflows
        </Typography>
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" onClick={() => setCreateOpen(true)}>
          New Dataflow
        </Button>
      </Stack>
      {dataflows.data?.length === 0 && (
        <Stack spacing={2} sx={{ alignItems: 'center', py: 6 }}>
          <Typography color="text.secondary">
            No Dataflows yet — a feed starts with a name and a blank canvas.
          </Typography>
          <Button variant="outlined" onClick={() => setCreateOpen(true)}>
            Create your first Dataflow
          </Button>
        </Stack>
      )}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
          gap: 2,
        }}
      >
        {dataflows.data?.map((dataflow) => (
          <DataflowCard
            key={dataflow.id}
            dataflow={dataflow}
            onOpen={() => navigate(`/dataflows/${dataflow.id}`)}
            onUndeploy={() => setPending({ action: 'undeploy', dataflow })}
            onDelete={() => setPending({ action: 'delete', dataflow })}
          />
        ))}
      </Box>
      <CreateDataflowDialog
        open={createOpen}
        pending={create.isPending}
        onCancel={() => setCreateOpen(false)}
        onConfirm={(name) => create.mutate(name)}
      />
      {pending !== null && (
        <ConfirmDialog
          open
          title={`${CONFIRM[pending.action].verb} "${pending.dataflow.name}"?`}
          message={CONFIRM[pending.action].message}
          confirmLabel={CONFIRM[pending.action].verb}
          pending={undeploy.isPending || remove.isPending}
          onCancel={() => setPending(null)}
          onConfirm={() =>
            (pending.action === 'undeploy' ? undeploy : remove).mutate(pending.dataflow.id)
          }
        />
      )}
    </Stack>
  );
}

const CONFIRM = {
  undeploy: {
    verb: 'Undeploy',
    message:
      'The Schedule stops firing and Run Now goes dark until the next deploy. The Draft is kept.',
  },
  delete: {
    verb: 'Delete',
    message: 'The Dataflow, its Deployments, and its run history are permanently removed.',
  },
} as const;

interface DataflowCardProps {
  dataflow: DataflowSummary;
  onOpen: () => void;
  onUndeploy: () => void;
  onDelete: () => void;
}

function DataflowCard({ dataflow, onOpen, onUndeploy, onDelete }: DataflowCardProps) {
  const deployed = dataflow.activeDeploymentVersion !== null;

  return (
    <Card variant="outlined" data-testid="dataflow-card">
      <CardActionArea onClick={onOpen}>
        <CardContent>
          <Typography variant="h6" component="h2">
            {dataflow.name}
          </Typography>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            {dataflow.slug}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
            <Chip
              size="small"
              label={
                deployed
                  ? `Deployed v${dataflow.activeDeploymentVersion}`
                  : 'Draft — not deployed'
              }
              color={deployed ? 'success' : 'default'}
            />
            {dataflow.draftDrifted && <Chip size="small" label="Draft ahead" color="warning" />}
          </Stack>
          <LastRun lastRun={dataflow.lastRun} />
        </CardContent>
      </CardActionArea>
      <CardActions>
        <Button size="small" disabled={!deployed} onClick={onUndeploy}>
          Undeploy
        </Button>
        {/* Greyed while deployed, mirroring the API's 409 — the UI never
            invites a call that must fail. The tooltip needs the span: a
            disabled button fires no hover events of its own. */}
        <Tooltip title={deployed ? 'Undeploy first — a deployed Dataflow cannot be deleted' : ''}>
          <span>
            <Button size="small" color="error" disabled={deployed} onClick={onDelete}>
              Delete
            </Button>
          </span>
        </Tooltip>
      </CardActions>
    </Card>
  );
}

/** The last-run chip: status + Business Date + how long ago — or the honest none. */
function LastRun({ lastRun }: { lastRun: DataflowSummary['lastRun'] }) {
  if (lastRun === null) {
    return (
      <Typography variant="body2" color="text.secondary">
        No Runs yet
      </Typography>
    );
  }
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      <RunStatusChip status={lastRun.status} />
      <Typography variant="body2" color="text.secondary">
        {lastRun.businessDate} · {relativeTime(lastRun.startedAt, new Date())}
      </Typography>
    </Stack>
  );
}
