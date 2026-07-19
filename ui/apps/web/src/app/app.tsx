import AppBar from '@mui/material/AppBar';
import Container from '@mui/material/Container';
import CssBaseline from '@mui/material/CssBaseline';
import Link from '@mui/material/Link';
import Toolbar from '@mui/material/Toolbar';
import { Link as RouterLink, Route, Routes } from 'react-router';
import { BuilderView } from '../views/builder';
import { DataflowListView } from '../views/dataflow-list';
import { RunHistoryView } from '../views/run-history';
import { ProblemSnackbar } from './problem-snackbar';

export function App() {
  return (
    <>
      <CssBaseline />
      <AppBar position="static">
        <Toolbar>
          <Link component={RouterLink} to="/" color="inherit" underline="none" variant="h6">
            Dataflow
          </Link>
        </Toolbar>
      </AppBar>
      <Container component="main" sx={{ py: 4 }}>
        <Routes>
          <Route path="/" element={<DataflowListView />} />
          <Route path="/dataflows/:id" element={<BuilderView />} />
          <Route path="/dataflows/:id/runs" element={<RunHistoryView />} />
        </Routes>
      </Container>
      <ProblemSnackbar />
    </>
  );
}

export default App;
