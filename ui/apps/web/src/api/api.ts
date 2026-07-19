/**
 * The one seam between the UI and the control plane. Paths are relative
 * `/api/...` only — dev and preview servers proxy them to the control plane, so
 * the bundle carries no base URL and needs no CORS.
 *
 * Errors arrive as RFC 9457 problem details; the 422s carry the structured
 * `violations` array (structural saves: `{message}`; semantic deploys:
 * `{rule, message}`). Anything unparseable still becomes an {@link ApiError} so
 * every failure reaches the snackbar with a human title.
 */

export interface Violation {
  message: string;
  /** Stable semantic rule id (deploy 422s only). */
  rule?: string;
}

export class ApiError extends Error {
  /** Problem-detail title — the snackbar headline. */
  readonly title: string;

  /** HTTP status, absent when the request never reached the control plane. */
  readonly status?: number;

  readonly detail?: string;

  readonly violations: Violation[];

  constructor(title: string, options: { status?: number; detail?: string; violations?: Violation[] } = {}) {
    super(options.detail ?? title);
    this.name = 'ApiError';
    this.title = title;
    this.status = options.status;
    this.detail = options.detail;
    this.violations = options.violations ?? [];
  }
}

interface RequestOptions {
  method?: string;
  /** JSON-serialized verbatim; the content type is set here. */
  body?: unknown;
}

export async function apiFetch<T = unknown>(path: string, options: RequestOptions = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      ...(options.body !== undefined && {
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(options.body),
      }),
    });
  } catch {
    throw new ApiError('The control plane is unreachable');
  }
  if (!response.ok) {
    throw await problemOf(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function problemOf(response: Response): Promise<ApiError> {
  const fallback = `Request failed (HTTP ${response.status})`;
  try {
    const problem = await response.json();
    return new ApiError(typeof problem.title === 'string' ? problem.title : fallback, {
      status: response.status,
      detail: typeof problem.detail === 'string' ? problem.detail : undefined,
      violations: Array.isArray(problem.violations) ? problem.violations : [],
    });
  } catch {
    return new ApiError(fallback, { status: response.status });
  }
}
