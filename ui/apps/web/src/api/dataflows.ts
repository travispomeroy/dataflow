import type { DataflowConfig } from 'dataflow-config';
import { apiFetch } from './api';

/** The closed four-state Run status model (control-plane `RunStatus`). */
export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/** One row of the card-ready list projection (`GET /api/dataflows`, issue #29). */
export interface DataflowSummary {
  id: string;
  slug: string;
  name: string;
  /** Version of the active Deployment; null when undeployed. */
  activeDeploymentVersion: number | null;
  /** True when the Draft has drifted ahead of the active Deployment. */
  draftDrifted: boolean;
  lastRun: {
    status: RunStatus;
    startedAt: string;
    endedAt: string | null;
    businessDate: string;
  } | null;
}

/** A Dataflow with its Draft — the mutable config the builder edits (`GET /api/dataflows/{id}`). */
export interface DataflowDraft {
  id: string;
  slug: string;
  name: string;
  config: DataflowConfig;
}

/** A blank canvas: structurally valid, saveable, deployable-nowhere-near. */
export const EMPTY_CONFIG: DataflowConfig = {
  nodes: [],
  edges: [],
  schedule: null,
  engine: null,
  executionModel: null,
};

/** One frozen Deployment of a Dataflow (`GET /api/dataflows/{id}/deployments`). */
export interface DeploymentSummary {
  version: number;
  deployedAt: string;
  active: boolean;
}

/** Query keys, shared so invalidation can never drift from the queries. */
export const dataflowKeys = {
  list: ['dataflows'] as const,
  detail: (id: string) => ['dataflows', id] as const,
  deployments: (id: string) => ['dataflows', id, 'deployments'] as const,
};

export function listDataflows() {
  return apiFetch<DataflowSummary[]>('/api/dataflows');
}

export function getDataflow(id: string) {
  return apiFetch<DataflowDraft>(`/api/dataflows/${id}`);
}

export function createDataflow(name: string) {
  return apiFetch<DataflowDraft>('/api/dataflows', {
    method: 'POST',
    body: { name, config: EMPTY_CONFIG },
  });
}

/** Explicit Save: persists the Draft as it stands. Structural 422 → ApiError. */
export function saveDataflow(id: string, name: string, config: DataflowConfig) {
  return apiFetch<DataflowDraft>(`/api/dataflows/${id}`, {
    method: 'PUT',
    body: { name, config },
  });
}

/** Deploys the Draft as it stands; the response carries the new frozen version. */
export function deployDataflow(id: string) {
  return apiFetch<DeploymentSummary>(`/api/dataflows/${id}/deploy`, { method: 'POST' });
}

export function listDeployments(id: string) {
  return apiFetch<DeploymentSummary[]>(`/api/dataflows/${id}/deployments`);
}
