import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { createDataflow, dataflowKeys, listDataflows } from '../api/dataflows';

/**
 * Placeholder list view (the M3.4 status board grows here): every Dataflow with
 * its deployment fact, plus the smallest possible create — a name and a blank
 * canvas. Creating navigates straight into the builder.
 */
export function DataflowListView() {
  const [name, setName] = useState('');
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const dataflows = useQuery({ queryKey: dataflowKeys.list, queryFn: listDataflows });
  const create = useMutation({
    mutationFn: createDataflow,
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: dataflowKeys.list });
      navigate(`/dataflows/${created.id}`);
    },
  });

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        Dataflows
      </Typography>
      <Stack
        component="form"
        direction="row"
        spacing={2}
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate(name);
        }}
      >
        <TextField
          label="Name"
          size="small"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <Button type="submit" variant="contained" disabled={create.isPending}>
          Create Dataflow
        </Button>
      </Stack>
      {dataflows.data?.length === 0 && (
        <Typography color="text.secondary">No Dataflows yet — create one above.</Typography>
      )}
      <List>
        {dataflows.data?.map((dataflow) => (
          <ListItemButton
            key={dataflow.id}
            onClick={() => navigate(`/dataflows/${dataflow.id}`)}
          >
            <ListItemText primary={dataflow.name} secondary={dataflow.slug} />
            <Chip
              size="small"
              label={
                dataflow.activeDeploymentVersion === null
                  ? 'Not deployed'
                  : `Deployed v${dataflow.activeDeploymentVersion}`
              }
              color={dataflow.activeDeploymentVersion === null ? 'default' : 'success'}
            />
          </ListItemButton>
        ))}
      </List>
    </Stack>
  );
}
