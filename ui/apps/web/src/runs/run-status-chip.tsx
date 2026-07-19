import Chip from '@mui/material/Chip';
import type { RunStatus } from '../api/dataflows';

const STATUS_COLOR = {
  QUEUED: 'default',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'error',
} as const satisfies Record<RunStatus, string>;

/** The four-state Run status as a chip — one color mapping for every view. */
export function RunStatusChip({ status }: { status: RunStatus }) {
  return <Chip size="small" label={status} color={STATUS_COLOR[status]} />;
}
