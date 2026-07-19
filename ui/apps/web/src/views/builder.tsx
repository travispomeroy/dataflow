import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import LinearProgress from '@mui/material/LinearProgress';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from '@xyflow/react';
import type { Connection, Edge as FlowEdge, XYPosition } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { validate } from 'dataflow-config';
import { useCallback, useMemo, useState } from 'react';
import type { DragEvent } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router';
import {
  dataflowKeys,
  deployDataflow,
  getDataflow,
  listDeployments,
  saveDataflow,
} from '../api/dataflows';
import type { DataflowDraft } from '../api/dataflows';
import { runNow } from '../api/runs';
import { nodeTypes } from '../builder/canvas-nodes';
import {
  builderEdge,
  builderNode,
  configEquals,
  fromConfig,
  mintNodeId,
  toConfig,
} from '../builder/graph';
import type { BuilderNode, NodePayload } from '../builder/graph';
import { JsonPreview } from '../builder/json-preview';
import { Palette, PALETTE_NODE_MIME } from '../builder/palette';
import { PropertyPanel } from '../builder/property-panel';
import { RunNowDialog } from '../builder/run-now-dialog';

/**
 * The builder (issue #33): a React Flow canvas editing the persisted Draft,
 * palette on the left, property panel on the right, explicit Save and a Deploy
 * gated by the courtesy validation mirror, live JSON preview below.
 */
export function BuilderView() {
  const { id } = useParams<'id'>();

  const dataflow = useQuery({
    queryKey: dataflowKeys.detail(id as string),
    queryFn: () => getDataflow(id as string),
  });

  if (dataflow.data === undefined) {
    return <LinearProgress />;
  }
  // Keyed by id so canvas state initializes exactly once per Draft.
  return <BuilderEditor key={dataflow.data.id} draft={dataflow.data} />;
}

function BuilderEditor({ draft }: { draft: DataflowDraft }) {
  const queryClient = useQueryClient();

  const [initial] = useState(() => fromConfig(draft.config));
  const [nodes, setNodes, onNodesChange] = useNodesState<BuilderNode>(initial.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>(initial.edges);
  const [settings, setSettings] = useState(initial.settings);
  const [savedConfig, setSavedConfig] = useState(draft.config);

  // The live in-memory config: what Save would persist, what the JSON preview
  // shows, and what the courtesy validation judges.
  const config = useMemo(() => toConfig(nodes, edges, settings), [nodes, edges, settings]);
  const dirty = !configEquals(config, savedConfig);
  const violations = useMemo(() => validate(config), [config]);

  const deployments = useQuery({
    queryKey: dataflowKeys.deployments(draft.id),
    queryFn: () => listDeployments(draft.id),
  });

  const save = useMutation({
    mutationFn: () => saveDataflow(draft.id, draft.name, config),
    onSuccess: async (response) => {
      // The control plane's canonical form of what was just saved — the dirty
      // flag now compares against exactly what is persisted.
      setSavedConfig(response.config);
      queryClient.setQueryData(dataflowKeys.detail(draft.id), response);
      await queryClient.invalidateQueries({ queryKey: dataflowKeys.list });
    },
  });

  const deploy = useMutation({
    mutationFn: () => deployDataflow(draft.id),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: dataflowKeys.deployments(draft.id) }),
        queryClient.invalidateQueries({ queryKey: dataflowKeys.runs(draft.id) }),
        queryClient.invalidateQueries({ queryKey: dataflowKeys.list }),
      ]),
  });

  const [runNowOpen, setRunNowOpen] = useState(false);
  const navigate = useNavigate();

  // Run Now executes the active Deployment's frozen plan, never the Draft — the
  // button therefore follows the deployment fact, not the canvas.
  const triggerRun = useMutation({
    mutationFn: (businessDate?: string) => runNow(draft.id, businessDate),
    onSuccess: async () => {
      setRunNowOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: dataflowKeys.runs(draft.id) }),
        queryClient.invalidateQueries({ queryKey: dataflowKeys.list }),
      ]);
      // The watching half starts immediately: land on the history, poll live.
      await navigate(`/dataflows/${draft.id}/runs`);
    },
  });

  // The deploy response is the freshest fact — the deployments refetch merely
  // keeps it honest afterwards.
  const activeVersion =
    deploy.data?.version ??
    deployments.data?.find((deployment) => deployment.active)?.version;

  const selected = nodes.find((node) => node.selected);

  const addNode = useCallback(
    (payload: NodePayload, position: XYPosition) =>
      setNodes((existing) => [
        ...existing,
        builderNode(mintNodeId(payload, existing.map((node) => node.id)), payload, position),
      ]),
    [setNodes],
  );

  const changePayload = useCallback(
    (nodeId: string, payload: NodePayload) =>
      setNodes((existing) =>
        existing.map((node) =>
          node.id === nodeId ? ({ ...node, data: { payload } } as BuilderNode) : node,
        ),
      ),
    [setNodes],
  );

  const onConnect = useCallback(
    (connection: Connection) =>
      setEdges((existing) =>
        existing.some(
          (edge) => edge.source === connection.source && edge.target === connection.target,
        )
          ? existing
          : [...existing, builderEdge(connection.source, connection.target)],
      ),
    [setEdges],
  );

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <Typography variant="h4" component="h1">
          {draft.name}
        </Typography>
        <Chip
          size="small"
          label={activeVersion === undefined ? 'Draft — not deployed' : `Deployed v${activeVersion}`}
          color={activeVersion === undefined ? 'default' : 'success'}
        />
        <Box sx={{ flex: 1 }} />
        {dirty && <Chip size="small" label="Unsaved changes" color="warning" />}
        <Button
          variant="outlined"
          disabled={!dirty || save.isPending}
          onClick={() => save.mutate()}
        >
          Save
        </Button>
        <Button
          variant="contained"
          disabled={dirty || violations.length > 0 || save.isPending || deploy.isPending}
          onClick={() => deploy.mutate()}
        >
          Deploy
        </Button>
        <Button
          variant="outlined"
          disabled={activeVersion === undefined}
          onClick={() => setRunNowOpen(true)}
        >
          Run Now
        </Button>
        <Link component={RouterLink} to={`/dataflows/${draft.id}/runs`}>
          Run history
        </Link>
      </Stack>
      <RunNowDialog
        open={runNowOpen}
        pending={triggerRun.isPending}
        onCancel={() => setRunNowOpen(false)}
        onConfirm={(businessDate) => triggerRun.mutate(businessDate)}
      />
      <Stack direction="row" spacing={2} sx={{ height: 560 }}>
        <Palette />
        <ReactFlowProvider>
          <BuilderCanvas
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onAddNode={addNode}
            fitInitialView={initial.nodes.length > 0}
          />
        </ReactFlowProvider>
        <PropertyPanel
          selected={selected}
          settings={settings}
          onSettingsChange={setSettings}
          onPayloadChange={changePayload}
          violations={violations}
          dirty={dirty}
        />
      </Stack>
      <JsonPreview config={config} />
    </Stack>
  );
}

interface BuilderCanvasProps {
  nodes: BuilderNode[];
  edges: FlowEdge[];
  onNodesChange: ReturnType<typeof useNodesState<BuilderNode>>[2];
  onEdgesChange: ReturnType<typeof useEdgesState<FlowEdge>>[2];
  onConnect: (connection: Connection) => void;
  onAddNode: (payload: NodePayload, position: XYPosition) => void;
  /**
   * Fit the viewport to the Draft's nodes on open. Only when it opens with
   * some: on a blank canvas React Flow defers fitView until the first nodes
   * measure — the first *drop* would yank the viewport to max zoom.
   */
  fitInitialView: boolean;
}

function BuilderCanvas({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onAddNode,
  fitInitialView,
}: BuilderCanvasProps) {
  const { screenToFlowPosition } = useReactFlow();

  const onDragOver = (event: DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  };

  const onDrop = (event: DragEvent) => {
    const data = event.dataTransfer.getData(PALETTE_NODE_MIME);
    if (data === '') {
      return;
    }
    event.preventDefault();
    onAddNode(
      JSON.parse(data) as NodePayload,
      screenToFlowPosition({ x: event.clientX, y: event.clientY }),
    );
  };

  return (
    <Box
      onDragOver={onDragOver}
      onDrop={onDrop}
      sx={{ flex: 1, border: 1, borderColor: 'divider', borderRadius: 1 }}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        fitView={fitInitialView}
      >
        <Background />
        <Controls />
      </ReactFlow>
    </Box>
  );
}
