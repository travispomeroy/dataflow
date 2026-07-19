import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink, useParams } from 'react-router';
import { dataflowKeys, getDataflow } from '../api/dataflows';
import { listRuns } from '../api/runs';
import type { RunSummary } from '../api/runs';
import { RunStatusChip } from '../runs/run-status-chip';

/** The control plane's own Kestra poll cadence — any faster would poll a poller. */
const RUN_POLL_MS = 5_000;

/**
 * Run history (issue #34): every Run of this Dataflow, newest first, polled at
 * the control plane's own Kestra cadence so a RUNNING → SUCCEEDED transition
 * lands on screen without a refresh. Delivered files appear only when a Run
 * succeeds — a FAILED Run honestly shows none.
 */
export function RunHistoryView() {
  const { id } = useParams<'id'>();

  const dataflow = useQuery({
    queryKey: dataflowKeys.detail(id as string),
    queryFn: () => getDataflow(id as string),
  });

  const runs = useQuery({
    queryKey: dataflowKeys.runs(id as string),
    queryFn: () => listRuns(id as string),
    refetchInterval: RUN_POLL_MS,
  });

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'baseline' }}>
        <Typography variant="h4" component="h1">
          Run history
        </Typography>
        {dataflow.data && <Typography color="text.secondary">{dataflow.data.name}</Typography>}
        <Link component={RouterLink} to={`/dataflows/${id}`}>
          Back to the builder
        </Link>
      </Stack>
      {runs.data?.length === 0 && (
        <Typography color="text.secondary">
          No Runs yet — Run Now in the builder starts one.
        </Typography>
      )}
      {runs.data !== undefined && runs.data.length > 0 && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Runs">
            <TableHead>
              <TableRow>
                <TableCell>Status</TableCell>
                <TableCell>Started</TableCell>
                <TableCell>Ended</TableCell>
                <TableCell>Business Date</TableCell>
                <TableCell>Delivered files</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {runs.data.map((run) => (
                <RunRow key={run.id} run={run} />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Stack>
  );
}

function RunRow({ run }: { run: RunSummary }) {
  return (
    <TableRow sx={{ verticalAlign: 'top' }}>
      <TableCell>
        <RunStatusChip status={run.status} />
      </TableCell>
      <TableCell>{localTime(run.startedAt)}</TableCell>
      <TableCell>{localTime(run.endedAt)}</TableCell>
      <TableCell>{run.businessDate}</TableCell>
      <TableCell>
        {run.deliveredFiles.length === 0 ? (
          '—'
        ) : (
          <Stack component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {run.deliveredFiles.map((file) => (
              <li key={file.name}>
                {file.name} — {file.records} records
              </li>
            ))}
          </Stack>
        )}
      </TableCell>
    </TableRow>
  );
}

/** Local wall-clock rendering of an ISO instant; a Run still running has no end. */
function localTime(iso: string | null) {
  return iso === null ? '—' : new Date(iso).toLocaleString();
}
