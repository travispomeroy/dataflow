import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import type { DataflowConfig } from 'dataflow-config';

/**
 * The collapsible live preview of the in-memory Dataflow Config — what Save
 * would persist right now. A teaching surface: the canvas demystifies the
 * document rather than hiding it.
 */
export function JsonPreview({ config }: { config: DataflowConfig }) {
  return (
    <Accordion disableGutters variant="outlined">
      <AccordionSummary expandIcon={<Typography component="span">▾</Typography>}>
        <Typography variant="subtitle2">Dataflow Config JSON</Typography>
      </AccordionSummary>
      <AccordionDetails>
        <Box
          component="pre"
          sx={{ m: 0, fontFamily: 'monospace', fontSize: 13, overflowX: 'auto' }}
        >
          {JSON.stringify(config, null, 2)}
        </Box>
      </AccordionDetails>
    </Accordion>
  );
}
