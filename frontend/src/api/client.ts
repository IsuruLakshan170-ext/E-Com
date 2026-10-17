import { type Session, tokenStore } from '../auth/tokenStore';
import { ApiError } from './errors';
import type { TokenResponse } from './types';

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

export type QueryValue = string | number | boolean | null | undefined;
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RequestOptions {
  method?: HttpMethod;
  body?: unknown;
  query?: Record<string, QueryValue>;
  /** Sent as the Idempotency-Key header: reuse the SAME key when retrying the same action. */
  idempotencyKey?: string;
  /** false for login/register/refresh/logout: never attach or refresh a token. */
  auth?: boolean;
  signal?: AbortSignal;
}

export interface ApiResult<T> {
  data: T;
  status: number;
  /** The backend answered from its idempotency record (header Idempotent-Replayed: true). */
  replayed: boolean;
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const params = new URLSearchParams();
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params.append(key, String(value));
      }
    }
  }
  const qs = params.toString();
  return `${BASE_URL}${path}${qs ? `?${qs}` : ''}`;
}

const REFRESH_LOCK = 'ecom-refresh';
const REFRESH_TIMEOUT_MS = 15_000;

let refreshInFlight: Promise<boolean> | null = null;
let refreshPausedUntil = 0;

/**
 * Makes sure this tab holds a usable access token.
 * - Resolves true when it does (rotated here, or adopted from another tab), false when there is no session or the
 *   backend refused it (the session has then ended and the AuthProvider was told why).
 * - Throws a retryable ApiError when the backend could not be reached or asked us to slow down (network error,
 *   429, 5xx). The session is KEPT (F2) and Retry-After is honoured before trying again.
 * `rejectedAccessToken` is the token a 401 just refused: if the shared session already holds a different one,
 * another tab refreshed first and that pair is adopted.
 * Single-flight in this tab and, across tabs, one refresh at a time under a Web Lock (F1): the backend treats a
 * refresh token used twice (outside its 30 s grace) as theft and signs the user out everywhere.
 */
export function refreshSession(rejectedAccessToken: string | null = null): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = refreshUnderLock(rejectedAccessToken).finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function refreshUnderLock(rejected: string | null): Promise<boolean> {
  const pausedFor = Math.ceil((refreshPausedUntil - Date.now()) / 1000);
  if (pausedFor > 0) {
    throw refreshPaused(pausedFor);
  }
  if (!tokenStore.hasSession()) {
    return false;
  }
  return withRefreshLock(async () => {
    // Another tab may have refreshed, signed out or signed in while this one waited for the lock.
    const latest = tokenStore.session();
    if (!latest) {
      return false;
    }
    const stillNeeded = rejected !== null ? latest.accessToken === rejected : !tokenStore.isFresh(latest);
    return stillNeeded ? rotate(latest, rejected !== null) : true;
  });
}

/** Holds the cross-tab lock while `work` runs. Without the Web Locks API, per-tab single-flight plus the backend's
 *  30 s grace window cover the rare race. */
function withRefreshLock<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === 'undefined' ? undefined : (navigator.locks as LockManager | undefined);
  if (!locks) {
    return work();
  }
  // The lock is held until the callback's promise settles; the result is handed out through our own promise.
  return new Promise<T>((resolve, reject) => {
    locks.request(REFRESH_LOCK, () => work().then(resolve, reject)).catch(reject);
  });
}

async function rotate(from: Session, after401: boolean): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REFRESH_TIMEOUT_MS); // never hold the lock forever
  let response: Response;
  try {
    response = await fetch(buildUrl('/api/v1/auth/refresh'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken: from.refreshToken }),
      credentials: 'omit',
      cache: 'no-store',
      signal: controller.signal,
    });
  } catch {
    throw ApiError.network(); // offline or timed out: keep the session (F2)
  } finally {
    clearTimeout(timer);
  }
  const body = await readBody(response);
  if (response.ok) {
    if (!isTokenResponse(body)) {
      throw ApiError.network(); // e.g. a proxy error page; the backend's 30 s grace makes a quick retry safe
    }
    refreshPausedUntil = 0;
    if (tokenStore.saveRotation(from, body, after401)) {
      return true;
    }
    return tokenStore.session()?.sid === from.sid; // same sign-in, already rotated elsewhere: use that one
  }
  if (response.status === 401 || response.status === 400) {
    tokenStore.endSession('expired', from.sid); // refused (expired, revoked, reused): this sign-in is over
    return false;
  }
  // 429 / 5xx / anything else: the refresh token is still good, so keep the session; the caller can retry (F2).
  const problem = ApiError.fromResponse(response.status, body, response.headers.get('Retry-After'));
  if (problem.retryAfterSeconds) {
    refreshPausedUntil = Date.now() + problem.retryAfterSeconds * 1000;
  }
  throw new ApiError({
    status: problem.status,
    code: problem.code,
    message: problem.message,
    retryable: true,
    requestId: problem.requestId,
    details: problem.details,
    retryAfterSeconds: problem.retryAfterSeconds,
  });
}

function isTokenResponse(body: unknown): body is TokenResponse {
  const t = body as Partial<TokenResponse> | null;
  return typeof t === 'object' && t !== null && typeof t.accessToken === 'string'
    && typeof t.refreshToken === 'string' && typeof t.expiresIn === 'number';
}

function refreshPaused(seconds: number): ApiError {
  return new ApiError({
    status: 429,
    code: 'RATE_LIMITED',
    message: `Too many requests. Please try again in ${seconds} s.`,
    retryable: true,
    retryAfterSeconds: seconds,
  });
}

async function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] = options.idempotencyKey;
  }
  try {
    return await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      credentials: 'omit', // bearer tokens only: no cookies, so no CSRF surface
      cache: 'no-store', // never cache authenticated responses
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw ApiError.network();
  }
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

async function parse<T>(response: Response): Promise<ApiResult<T>> {
  const replayed = response.headers.get('Idempotent-Replayed') === 'true';
  if (response.status === 204) {
    return { data: undefined as T, status: 204, replayed };
  }
  const body = await readBody(response);
  if (!response.ok) {
    throw ApiError.fromResponse(response.status, body, response.headers.get('Retry-After'));
  }
  return { data: body as T, status: response.status, replayed };
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  const withAuth = options.auth !== false;
  if (withAuth && tokenStore.needsRefresh()) {
    await refreshSession(); // throws a retryable error when offline/429/5xx: nothing has been sent yet (F2)
  }
  const sentWith = withAuth ? tokenStore.getAccessToken() : null;
  let response = await send(path, options, sentWith);
  // A 401 for a token we sent means it was rejected (expired early, or revoked by a token-version bump after a role
  // change): refresh (or adopt another tab's refresh) once and retry. The request never ran, so retrying is safe.
  if (response.status === 401 && sentWith !== null && tokenStore.hasSession()) {
    if (await refreshSession(sentWith)) {
      tokenStore.markScopesStale(); // F3: the AuthProvider re-reads whoami
      response = await send(path, options, tokenStore.getAccessToken());
    }
  }
  return parse<T>(response);
}

export async function api<T>(path: string, options?: RequestOptions): Promise<T> {
  return (await request<T>(path, options)).data;
}
