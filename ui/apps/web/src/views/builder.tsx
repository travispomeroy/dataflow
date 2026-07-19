import Link from '@mui/material/Link';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink, useParams } from 'react-router';
import { listSources } from '../api/catalog';
import { dataflowKeys, getDataflow } from '../api/dataflows';

/**
 * Placeholder builder view (the M3.5 canvas grows here): loads the Draft it
 * will edit and the Catalog Sources the palette will offer.
 */
export function BuilderView() {
  const { id } = useParams<'id'>();

  const dataflow = useQuery({
    queryKey: dataflowKeys.detail(id as string),
    queryFn: () => getDataflow(id as string),
  });
  const sources = useQuery({ queryKey: ['catalog', 'sources'], queryFn: listSources });

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {dataflow.data?.name ?? 'Builder'}
      </Typography>
      <Typography color="text.secondary">
        The canvas arrives with M3.5 — this Draft has {dataflow.data?.config.nodes.length ?? 0}{' '}
        nodes. <Link component={RouterLink} to={`/dataflows/${id}/runs`}>Run history</Link>
      </Typography>
      <Typography variant="h6" component="h2">
        Catalog Sources
      </Typography>
      <List dense>
        {sources.data?.map((source) => (
          <ListItem key={source.id}>
            <ListItemText primary={source.name} secondary={source.description} />
          </ListItem>
        ))}
      </List>
    </Stack>
  );
}
