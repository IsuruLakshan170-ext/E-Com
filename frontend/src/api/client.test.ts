import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../auth/tokenStore';
import { json, tokens } from '../test/fixtures';
import { refreshSession, request } from './client';
import { ApiError } from './errors';

function headersOf(call: unknown[]): Record<string, string> {
  return (call[1] as RequestInit).headers as Record<string, string>;
}

describe('api client', () => {
  beforeEach(() => tokenStore.clear());
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    tokenStore.clear();
  });

  it('refreshes once on 401, retries with the new access token and asks for fresh scopes (F3)', async () => {
    tokenStore.startSession(tokens('old', 'r1'));
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(401, { code: 'INVALID_TOKEN', detail: 'expired' }))
      .mockResolvedValueOnce(json(200, tokens('new', 'r2')))
      .mockResolvedValueOnce(json(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    const stale = vi.fn();
    const off = tokenStore.onSessionEvent((event) => event.type === 'scopes-stale' && stale(event.external));

    const result = await request<{ ok: boolean }>('/api/v1/cart');

    expect(result.data).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(headersOf(fetchMock.mock.calls[0])['Authorization']).toBe('Bearer old');
    expect(headersOf(fetchMock.mock.calls[2])['Authorization']).toBe('Bearer new');
    expect(tokenStore.getRefreshToken()).toBe('r2');
    expect(stale).toHaveBeenCalledWith(false);
    off();
  });

  it('shares one refresh between concurrent callers (the backend treats token reuse as theft)', async () => {
    tokenStore.startSession(tokens('a', 'r1', 10)); // about to expire
    const fetchMock = vi.fn().mockResolvedValue(json(200, tokens('b', 'r2')));
    vi.stubGlobal('fetch', fetchMock);

    const results = await Promise.all([refreshSession(), refreshSession(), refreshSession()]);

    expect(results).toEqual([true, true, true]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not rotate a token that is still fresh', async () => {
    tokenStore.startSession(tokens('a', 'r1'));
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    expect(await refreshSession()).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([401, 400])('ends the session when the refresh is refused with %i', async (status) => {
    tokenStore.startSession(tokens('a', 'r1', 10));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(status, { code: status === 401 ? 'INVALID_TOKEN' : 'VALIDATION_FAILED' })));
    const ended = vi.fn();
    const off = tokenStore.onSessionEnded(ended);

    expect(await refreshSession()).toBe(false);
    expect(ended).toHaveBeenCalledWith('expired', false);
    expect(tokenStore.hasSession()).toBe(false);
    off();
  });

  it('keeps the session when the refresh hits a server error (F2)', async () => {
    tokenStore.startSession(tokens('a', 'r1', 10));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(503, { code: 'SERVICE_UNAVAILABLE', detail: 'Down for maintenance' })));
    const ended = vi.fn();
    const off = tokenStore.onSessionEnded(ended);

    await expect(refreshSession()).rejects.toMatchObject({ status: 503, retryable: true });
    expect(tokenStore.hasSession()).toBe(true);
    expect(ended).not.toHaveBeenCalled();
    off();
  });

  it('keeps the session offline and fails the request with a retryable error instead of sending it unsigned (F2)', async () => {
    tokenStore.startSession(tokens('a', 'r1', 10));
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);

    const error = await request('/api/v1/cart').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('NETWORK_ERROR');
    expect((error as ApiError).retryable).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1); // the refresh only: the request itself was never sent
    expect(tokenStore.hasSession()).toBe(true);
  });

  it('honours Retry-After on 429 before trying the refresh again (F2)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2030-01-01T00:00:00Z'));
    tokenStore.startSession(tokens('a', 'r1', 10));
    const fetchMock = vi.fn().mockResolvedValueOnce(json(429, { code: 'RATE_LIMITED', retryable: true }, { 'Retry-After': '30' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(refreshSession()).rejects.toMatchObject({ code: 'RATE_LIMITED', retryAfterSeconds: 30 });
    expect(tokenStore.hasSession()).toBe(true);

    vi.setSystemTime(new Date('2030-01-01T00:00:10Z'));
    await expect(refreshSession()).rejects.toMatchObject({ code: 'RATE_LIMITED', retryAfterSeconds: 20 });
    expect(fetchMock).toHaveBeenCalledTimes(1); // paused: the server was not asked again

    vi.setSystemTime(new Date('2030-01-01T00:00:31Z'));
    fetchMock.mockResolvedValueOnce(json(200, tokens('b', 'r2')));
    expect(await refreshSession()).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('sends the Idempotency-Key and reports replays', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, { id: 'o1' }, { 'Idempotent-Replayed': 'true' }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await request('/api/v1/orders', { method: 'POST', body: {}, idempotencyKey: 'key-12345678', auth: false });

    expect(headersOf(fetchMock.mock.calls[0])['Idempotency-Key']).toBe('key-12345678');
    expect(result.replayed).toBe(true);
  });

  it('turns problem+json into an ApiError and network failures into NETWORK_ERROR', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json(404, { code: 'NOT_FOUND', detail: 'Order not found' })).mockRejectedValueOnce(new TypeError('offline')));

    await expect(request('/api/v1/orders/x', { auth: false })).rejects.toMatchObject({ code: 'NOT_FOUND', status: 404 });
    const offline = await request('/api/v1/orders/x', { auth: false }).catch((e: unknown) => e);
    expect(offline).toBeInstanceOf(ApiError);
    expect((offline as ApiError).code).toBe('NETWORK_ERROR');
    expect((offline as ApiError).retryable).toBe(true);
  });
});
