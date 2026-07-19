import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import TextField from '@mui/material/TextField';
import { useEffect, useState } from 'react';

interface CreateDataflowDialogProps {
  open: boolean;
  /** True while the create request is in flight — confirm is held to one fire. */
  pending: boolean;
  onCancel: () => void;
  onConfirm: (name: string) => void;
}

/**
 * New Dataflow (issue #32): name first, because the slug is minted from the
 * initial name and is the Dataflow's identity for life. Confirming creates a
 * persisted Draft with an empty config and lands on the builder.
 */
export function CreateDataflowDialog({
  open,
  pending,
  onCancel,
  onConfirm,
}: CreateDataflowDialogProps) {
  const [name, setName] = useState('');

  useEffect(() => {
    if (open) {
      setName('');
    }
  }, [open]);

  return (
    <Dialog open={open} onClose={onCancel} fullWidth maxWidth="xs">
      <DialogTitle>New Dataflow</DialogTitle>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          onConfirm(name.trim());
        }}
      >
        <DialogContent>
          <TextField
            label="Name"
            helperText="The slug is minted from this name and never changes."
            value={name}
            onChange={(event) => setName(event.target.value)}
            fullWidth
            margin="dense"
            autoFocus
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={onCancel}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={name.trim() === '' || pending}>
            Create
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
