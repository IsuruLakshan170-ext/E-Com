import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, tokens } from '../test/fixtures';

type ClientModule = typeof import('../api/client');
type TokenStore = (typeof import('./tokenStore'))['tokenStore'];

interface Tab {
  client: ClientModule;
  store: TokenStore;
}

/** A browser tab: its own copy of the modules (its memory), sharing localStorage and the lock manager with the others. */
async function openTab(): Promise<Tab> {
  vi.resetModules();
  const client = await import('../api/client');
  const { tokenStore } = await import('./tokenStore');
  return { client, store: tokenStore };
}

/** What the browser fires in every OTHER tab after one tab writes to localStorage. */
function storageEventInOtherTabs(): void {
  window.dispatchEvent(new StorageEvent('storage', { key: 'ecom.session' }));
}

/** A minimal Web Locks manager shared by all tabs: one holder per name, first come first served. */
function installWebLocks(): void {
  const tails = new Map<string, Promise<unknown>>();
  const locks = {
    request(name: string, callback: () => Promise<unknown>): Promise<unknown> {
      const run = (tails.get(name) ?? Promise.resolve()).then(() => callback());
      tails.set(name, run.catch(() => undefined));
      return run;
    },
  };
  Object.defineProperty(window.navigator, 'locks', { value: locks, configurable: true });
}

/** The backend's rotation rule: only the newest refresh token works; any other use is reuse (theft) -> 401. */
function authServer(first: string) {
  let valid = first;
  let issued = 1;
  const presented: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/api/v1/auth/refresh')) {
        const { refreshToken } = JSON.parse(String(init?.body)) as { refreshToken: string };
        presented.push(refreshToken);
        if (refreshToken !== valid) {
          return json(401, { code: 'INVALID_TOKEN', detail: 'Refresh token is invalid, expired or already used' });
        }
        issued += 1;
        valid = `r${issued}`;
        return json(200, tokens(`a${issued}`, valid));
      }
      return json(200, { ok: true });
    }),
  );
  return { presented };
}

describe('one session shared by every tab (F1)', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    Reflect.deleteProperty(window.navigator, 'locks');
    localStorage.clear();
  });

  it('a new or duplicated tab joins the session instead of asking to sign in again', async () => {
    const a = await openTab();
    a.store.startSession(tokens('a1', 'r1'));

    const b = await openTab();

    expect(b.store.hasSession()).toBe(true);
    expect(b.store.getAccessToken()).toBe('a1');
  });

  it('two tabs refreshing at the same moment rotate the refresh token only once', async () => {
    installWebLocks();
    const server = authServer('r1');
    const a = await openTab();
    a.store.startSession(tokens('a1', 'r1', 10)); // about to expire
    const b = await openTab();

    const results = await Promise.all([a.client.refreshSession(), b.client.refreshSession()]);

    expect(results).toEqual([true, true]);
    expect(server.presented).toEqual(['r1']); // B waited for the lock and adopted A's pair: no reuse
    expect(a.store.getAccessToken()).toBe('a2');
    expect(b.store.getAccessToken()).toBe('a2');
  });

  it('a tab whose request got a 401 adopts the pair another tab already rotated', async () => {
    installWebLocks();
    const server = authServer('r1');
    const a = await openTab();
    a.store.startSession(tokens('a1', 'r1'));
    const b = await openTab();

    expect(await a.client.refreshSession('a1')).toBe(true); // A: token rejected -> r1 rotated to r2
    expect(await b.client.refreshSession('a1')).toBe(true); // B: the same rejected token -> adopt, don't reuse r1

    expect(server.presented).toEqual(['r1']);
    expect(b.store.getAccessToken()).toBe('a2');
  });

  it('a duplicated tab later refreshes with the newest token, never the copy it started with', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2030-01-01T00:00:00Z'));
    installWebLocks();
    const server = authServer('r1');
    const a = await openTab();
    a.store.startSession(tokens('a1', 'r1', 10));
    const b = await openTab(); // starts with r1, like a duplicated tab

    await a.client.refreshSession(); // r1 -> r2, valid for 15 minutes
    vi.setSystemTime(new Date('2030-01-01T00:14:50Z')); // inside the refresh margin again
    await b.client.request('/api/v1/cart');

    expect(server.presented).toEqual(['r1', 'r2']);
    expect(b.store.hasSession()).toBe(true);
    expect(a.store.getAccessToken()).toBe('a3');
  });

  it('signing out in one tab signs the other tabs out, with the reason', async () => {
    const a = await openTab();
    a.store.startSession(tokens('a1', 'r1'));
    const b = await openTab();
    const ended = vi.fn();
    b.store.onSessionEnded(ended);

    a.store.endSession('revoked');
    storageEventInOtherTabs();

    expect(ended).toHaveBeenCalledWith('revoked', true);
    expect(b.store.hasSession()).toBe(false);
  });

  it('a sign-in in another tab is announced, so this tab can load the user', async () => {
    const a = await openTab();
    const b = await openTab();
    const started = vi.fn();
    b.store.onSessionEvent((event) => event.type === 'started' && started(event.external));

    a.store.startSession(tokens('a1', 'r1'));
    storageEventInOtherTabs();

    expect(started).toHaveBeenCalledWith(true);
    expect(b.store.getAccessToken()).toBe('a1');
  });

  it('a refresh that finishes after someone signed in again elsewhere never overwrites the new sign-in', async () => {
    const a = await openTab();
    a.store.startSession(tokens('a1', 'r1', 10));
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        await gate;
        return json(200, tokens('a2', 'r2'));
      }),
    );

    const pending = a.client.refreshSession(); // A rotates the old sign-in...
    const b = await openTab();
    b.store.startSession(tokens('b1', 'rb1')); // ...while the user signs in again in B
    release();

    expect(await pending).toBe(false); // the stale result is dropped
    expect(b.store.getAccessToken()).toBe('b1');
    expect(a.store.getAccessToken()).toBe('b1'); // A follows the newer sign-in
  });

  it('a refused refresh for an old sign-in does not end a newer one', async () => {
    const a = await openTab();
    a.store.startSession(tokens('a1', 'r1', 10));
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        await gate;
        return json(401, { code: 'INVALID_TOKEN' });
      }),
    );

    const pending = a.client.refreshSession();
    const b = await openTab();
    b.store.startSession(tokens('b1', 'rb1'));
    release();

    expect(await pending).toBe(false);
    expect(b.store.hasSession()).toBe(true);
    expect(b.store.getAccessToken()).toBe('b1');
  });
});
