import { afterEach, describe, expect, it, vi } from 'vitest';
import { listRuns, runNow } from './runs';

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** The 202 body run-now answers with — the QUEUED Run, straight from M2's walkthrough. */
const queuedRun = {
  id: 'a7dd6a67-0000-0000-0000-000000000000',
  status: 'QUEUED',
  detail: null,
  kestraExecutionId: 'exec-1',
  startedAt: '2026-07-19T10:00:00Z',
  endedAt: null,
  businessDate: '2026-07-17',
  deliveredFiles: [],
};

describe('runNow', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('posts the Business Date override when one is set', async () => {
    const fetchMock = stubFetch(jsonResponse(202, queuedRun));

    const run = await runNow('df-1', '2026-07-17');

    expect(fetchMock).toHaveBeenCalledWith('/api/dataflows/df-1/run-now', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ businessDate: '2026-07-17' }),
    });
    expect(run).toEqual(queuedRun);
  });

  it('sends no body at all when the Business Date is left empty', async () => {
    // Absent body = the compiled flow's run-date default: today.
    const fetchMock = stubFetch(jsonResponse(202, queuedRun));

    await runNow('df-1');

    expect(fetchMock).toHaveBeenCalledWith('/api/dataflows/df-1/run-now', {
      method: 'POST',
    });
  });
});

describe('listRuns', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('fetches the run history with delivered file names and record counts intact', async () => {
    const succeeded = {
      ...queuedRun,
      status: 'SUCCEEDED',
      detail: 'SUCCESS',
      endedAt: '2026-07-19T10:01:30Z',
      deliveredFiles: [
        { name: 'positions_equity_2026-07-17.csv', records: 12 },
        { name: 'positions_fixed_income_2026-07-17.csv', records: 7 },
      ],
    };
    const fetchMock = stubFetch(jsonResponse(200, [succeeded]));

    const runs = await listRuns('df-1');

    expect(fetchMock).toHaveBeenCalledWith('/api/dataflows/df-1/runs', { method: 'GET' });
    expect(runs).toEqual([succeeded]);
  });
});
