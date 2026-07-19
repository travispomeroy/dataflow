import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  /** The confirm button's label — the verb, so the choice reads unambiguously. */
  confirmLabel: string;
  /** True while the confirmed request is in flight — confirm is held to one fire. */
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

/** One question, two buttons — the card's undeploy and delete both ask through here. */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  pending,
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  // While the confirmed call is in flight the dialog holds: dismissing
  // mid-destruction would look like a cancel that isn't one.
  return (
    <Dialog open={open} onClose={pending ? undefined : onCancel} fullWidth maxWidth="xs">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <DialogContentText>{message}</DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button disabled={pending} onClick={onCancel}>
          Cancel
        </Button>
        <Button variant="contained" color="error" disabled={pending} onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
