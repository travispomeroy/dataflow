import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from './api';

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function jsonResponse(status: number, body: unknown, contentType = 'application/json') {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': contentType },
  });
}

describe('apiFetch', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('returns the parsed body on success', async () => {
    stubFetch(jsonResponse(200, [{ id: 'positions', name: 'Positions' }]));

    const body = await apiFetch<{ id: string; name: string }[]>('/api/catalog/sources');

    expect(body).toEqual([{ id: 'positions', name: 'Positions' }]);
  });

  it('sends JSON bodies with the content type set', async () => {
    const fetchMock = stubFetch(jsonResponse(201, { id: 'x' }));

    await apiFetch('/api/dataflows', {
      method: 'POST',
      body: { name: 'Positions Feed', config: null },
    });

    expect(fetchMock).toHaveBeenCalledWith('/api/dataflows', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ name: 'Positions Feed', config: null }),
    });
  });

  it('returns undefined on 204 No Content', async () => {
    stubFetch(new Response(null, { status: 204 }));

    await expect(apiFetch('/api/dataflows/x', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('parses a structural 422 problem detail into title and violations', async () => {
    // The exact shape StructuralViolationsException produces.
    stubFetch(
      jsonResponse(
        422,
        {
          type: 'about:blank',
          title: 'Structural validation failed',
          status: 422,
          detail: 'The Dataflow Config is not structurally valid',
          violations: [{ message: 'config is required' }],
        },
        'application/problem+json',
      ),
    );

    const error = await apiFetch('/api/dataflows', { method: 'POST', body: {} }).catch(
      (e: unknown) => e,
    );

    expect(error).toBeInstanceOf(ApiError);
    const problem = error as ApiError;
    expect(problem.status).toBe(422);
    expect(problem.title).toBe('Structural validation failed');
    expect(problem.violations).toEqual([{ message: 'config is required' }]);
  });

  it('keeps semantic violation rule ids', async () => {
    stubFetch(
      jsonResponse(
        422,
        {
          title: 'Semantic validation failed',
          status: 422,
          violations: [{ rule: 'no-source', message: 'The Dataflow has no Source' }],
        },
        'application/problem+json',
      ),
    );

    const error = (await apiFetch('/api/dataflows/x/deploy', { method: 'POST' }).catch(
      (e: unknown) => e,
    )) as ApiError;

    expect(error.violations).toEqual([
      { rule: 'no-source', message: 'The Dataflow has no Source' },
    ]);
  });

  it('falls back to the HTTP status when the error body is not a problem detail', async () => {
    stubFetch(
      new Response('<html>Bad Gateway</html>', {
        status: 502,
        statusText: 'Bad Gateway',
        headers: { 'content-type': 'text/html' },
      }),
    );

    const error = (await apiFetch('/api/catalog/sources').catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(502);
    expect(error.title).toBe('Request failed (HTTP 502)');
    expect(error.violations).toEqual([]);
  });

  it('surfaces a network failure as an ApiError with no status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    const error = (await apiFetch('/api/catalog/sources').catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBeUndefined();
    expect(error.title).toBe('The control plane is unreachable');
  });
});
