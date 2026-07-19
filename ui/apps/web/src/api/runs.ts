import { apiFetch } from './api';
import type { RunStatus } from './dataflows';

/** One delivered output file of a terminal SUCCEEDED Run. */
export interface DeliveredFile {
  name: string;
  records: number;
}

/** One Run of a deployed Dataflow (`GET /api/dataflows/{id}/runs`, issue #26). */
export interface RunSummary {
  id: string;
  status: RunStatus;
  detail: string | null;
  kestraExecutionId: string | null;
  startedAt: string;
  endedAt: string | null;
  businessDate: string;
  /** Empty until a Run succeeds — and empty forever on FAILED. */
  deliveredFiles: DeliveredFile[];
}

/** Run history, newest first — exactly the control plane's ordering. */
export function listRuns(dataflowId: string) {
  return apiFetch<RunSummary[]>(`/api/dataflows/${dataflowId}/runs`);
}

/**
 * Triggers the active Deployment now (issue #25). The Business Date override is
 * sent only when set — absent, the control plane passes no input and the
 * compiled flow's own run-date default resolves it to today.
 */
export function runNow(dataflowId: string, businessDate?: string) {
  return apiFetch<RunSummary>(`/api/dataflows/${dataflowId}/run-now`, {
    method: 'POST',
    ...(businessDate !== undefined && { body: { businessDate } }),
  });
}
