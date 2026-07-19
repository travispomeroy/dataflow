import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Snackbar from '@mui/material/Snackbar';
import { useEffect, useState } from 'react';
import { ApiError } from '../api/api';

/**
 * The single error surface: every failed API call — query or mutation — lands
 * here via the query client's caches, as an RFC 9457 title plus the structured
 * violations, phrased exactly as the control plane sent them.
 */

type Listener = (problem: ApiError) => void;

const listeners = new Set<Listener>();

export function publishProblem(error: unknown) {
  const problem =
    error instanceof ApiError
      ? error
      : new ApiError('Something went wrong', { detail: String(error) });
  listeners.forEach((listener) => listener(problem));
}

export function ProblemSnackbar() {
  const [problem, setProblem] = useState<ApiError | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const listener: Listener = (next) => {
      setProblem(next);
      setOpen(true);
    };
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);

  return (
    <Snackbar
      open={open}
      autoHideDuration={6000}
      onClose={(_, reason) => reason !== 'clickaway' && setOpen(false)}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
    >
      <Alert severity="error" variant="filled" onClose={() => setOpen(false)}>
        <AlertTitle>{problem?.title}</AlertTitle>
        {problem?.violations.length ? (
          <ul style={{ margin: 0, paddingInlineStart: '1.2em' }}>
            {problem.violations.map((violation, index) => (
              <li key={index}>{violation.message}</li>
            ))}
          </ul>
        ) : (
          problem?.detail
        )}
      </Alert>
    </Snackbar>
  );
}
