import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink, useParams } from 'react-router';

/**
 * Placeholder run history view (M3.6 brings runs, live polling, and delivered
 * files) — here only so the route exists and deep-links.
 */
export function RunHistoryView() {
  const { id } = useParams<'id'>();

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        Run history
      </Typography>
      <Typography color="text.secondary">
        Runs arrive with M3.6.{' '}
        <Link component={RouterLink} to={`/dataflows/${id}`}>
          Back to the builder
        </Link>
      </Typography>
    </Stack>
  );
}
