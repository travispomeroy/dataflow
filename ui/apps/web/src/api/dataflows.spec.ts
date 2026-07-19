import { afterEach, describe, expect, it, vi } from 'vitest';
import { deleteDataflow, undeployDataflow } from './dataflows';

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('undeployDataflow', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('posts to the undeploy endpoint and resolves on 204', async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));

    await expect(undeployDataflow('df-1')).resolves.toBeUndefined();

    expect(fetchMock).toHaveBeenCalledWith('/api/dataflows/df-1/undeploy', {
      method: 'POST',
    });
  });
});

describe('deleteDataflow', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('deletes the Dataflow and resolves on 204', async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));

    await expect(deleteDataflow('df-1')).resolves.toBeUndefined();

    expect(fetchMock).toHaveBeenCalledWith('/api/dataflows/df-1', {
      method: 'DELETE',
    });
  });

  it('surfaces the deployed-Dataflow 409 as an ApiError', async () => {
    // The UI greys delete while deployed, but the API's refusal must still
    // arrive as a problem detail if a stale card ever lets the call through.
    stubFetch(
      new Response(
        JSON.stringify({
          title: 'Conflict',
          status: 409,
          detail: "Dataflow 'positions-feed' is deployed — undeploy it first",
        }),
        { status: 409, headers: { 'content-type': 'application/problem+json' } },
      ),
    );

    await expect(deleteDataflow('df-1')).rejects.toMatchObject({
      name: 'ApiError',
      status: 409,
      detail: "Dataflow 'positions-feed' is deployed — undeploy it first",
    });
  });
});
