import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import TextField from '@mui/material/TextField';
import { useEffect, useState } from 'react';

interface RunNowDialogProps {
  open: boolean;
  /** True while the run-now request is in flight — confirm is held to one fire. */
  pending: boolean;
  onCancel: () => void;
  /** Confirmed with the Business Date override, or undefined when left empty. */
  onConfirm: (businessDate?: string) => void;
}

/**
 * Run Now (issue #34): the one question a manual run needs — which Business
 * Date the data is as-of. Empty means today; the override exists so yesterday's
 * feed can be regenerated when a provider asks.
 */
export function RunNowDialog({ open, pending, onCancel, onConfirm }: RunNowDialogProps) {
  const [businessDate, setBusinessDate] = useState('');

  // Every opening starts empty — a stale override from the last run would
  // silently contradict the "empty = today" promise.
  useEffect(() => {
    if (open) {
      setBusinessDate('');
    }
  }, [open]);

  return (
    <Dialog open={open} onClose={onCancel} fullWidth maxWidth="xs">
      <DialogTitle>Run Now</DialogTitle>
      <DialogContent>
        <TextField
          type="date"
          label="Business Date"
          helperText="Empty = today. Stamped into the delivered file names."
          value={businessDate}
          onChange={(event) => setBusinessDate(event.target.value)}
          fullWidth
          margin="dense"
          slotProps={{ inputLabel: { shrink: true } }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button
          variant="contained"
          disabled={pending}
          onClick={() => onConfirm(businessDate === '' ? undefined : businessDate)}
        >
          Run Now
        </Button>
      </DialogActions>
    </Dialog>
  );
}
