import { newIdempotencyKey } from '../api/idempotency';
import type { TokenResponse } from '../api/types';

/**
 * Where the session lives (F1): ONE session per browser, shared by every tab.
 * - localStorage `ecom.session` holds the token pair, so a new tab, a duplicated tab or a reload joins the same
 *   session instead of asking to sign in again, or (worse) rotating a copied refresh token in parallel, which the
 *   backend treats as theft and answers by signing the user out everywhere.
 * - Refreshes run one at a time across tabs under a Web Lock (api/client.ts). A tab that waited for the lock
 *   re-reads this record and adopts the rotated pair, so no refresh token is ever used twice.
 * - Sign-out, expiry and sign-in reach the other tabs through the `storage` event; a small tombstone
 *   `{ ended: reason }` tells them why a session ended.
 * Exposure: scripts can read localStorage exactly like sessionStorage (only the lifetime differs), so strict CSP
 * plus React's escaping remain the XSS defence. The access token sits next to the refresh token because anything
 * that can read one can read the other. An httpOnly-cookie refresh (BFF) is the planned end state (NOTES.md).
 * If storage is blocked (privacy settings), the session lives in this tab's memory only, as before.
 */
export type SessionEndReason = 'logout' | 'expired' | 'revoked';

export type SessionEvent =
  /** A new sign-in; `external` when it happened in another tab (this tab then loads the user). */
  | { type: 'started'; external: boolean }
  /** F3: a 401 was answered by a refresh, so the token's scopes may have changed (e.g. a role change). */
  | { type: 'scopes-stale'; external: boolean }
  | { type: 'ended'; external: boolean; reason: SessionEndReason };

export interface Session {
  /** One sign-in. Stays the same across refreshes, so tabs can tell a rotation from a different sign-in. */
  sid: string;
  accessToken: string;
  accessExpiresAt: number;
  refreshToken: string;
  /** The last rotation answered a 401 (other tabs then re-read their scopes, F3). */
  after401: boolean;
}

interface Ended {
  ended: SessionEndReason;
  at: number;
}

const SESSION_KEY = 'ecom.session';
const REFRESH_MARGIN_MS = 30_000;
const END_REASONS: readonly SessionEndReason[] = ['logout', 'expired', 'revoked'];

/** This tab's view of the session (and the last state its listeners were told about). */
let current: Session | null = null;
let storageUsable = true;
const listeners = new Set<(event: SessionEvent) => void>();

function storage(): Storage | null {
  if (!storageUsable) {
    return null;
  }
  try {
    return window.localStorage;
  } catch {
    storageUsable = false;
    return null;
  }
}

function isSession(value: unknown): value is Session {
  const v = value as Partial<Session> | null;
  return typeof v === 'object' && v !== null && typeof v.sid === 'string' && typeof v.accessToken === 'string'
    && typeof v.refreshToken === 'string' && typeof v.accessExpiresAt === 'number';
}

function isEnded(value: unknown): value is Ended {
  const v = value as Partial<Ended> | null;
  return typeof v === 'object' && v !== null && END_REASONS.includes(v.ended as SessionEndReason);
}

/** undefined: storage unusable (memory only); null: nothing (or nothing readable) stored. */
function readStored(): Session | Ended | null | undefined {
  const store = storage();
  if (!store) {
    return undefined;
  }
  let raw: string | null;
  try {
    raw = store.getItem(SESSION_KEY);
  } catch {
    storageUsable = false;
    return undefined;
  }
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    return isSession(value) || isEnded(value) ? value : null;
  } catch {
    return null;
  }
}

function write(value: Session | Ended): void {
  const store = storage();
  try {
    store?.setItem(SESSION_KEY, JSON.stringify(value));
  } catch {
    storageUsable = false; // quota / old private modes: carry on in memory for this tab
  }
}

function emit(event: SessionEvent): void {
  listeners.forEach((listener) => listener(event));
}

/**
 * The current session, re-read from storage (the shared truth) whenever storage works. A change made by another
 * tab is announced to this tab's listeners the first time it is seen, whether via the `storage` event or a request.
 */
function session(): Session | null {
  const stored = readStored();
  if (stored === undefined) {
    return current;
  }
  const next = stored !== null && isSession(stored) ? stored : null;
  const previous = current;
  current = next;
  if (next && (!previous || previous.sid !== next.sid)) {
    emit({ type: 'started', external: true });
  } else if (next && previous && next.refreshToken !== previous.refreshToken && next.after401) {
    emit({ type: 'scopes-stale', external: true });
  } else if (!next && previous) {
    emit({ type: 'ended', external: true, reason: stored !== null && isEnded(stored) ? stored.ended : 'logout' });
  }
  return current;
}

function toSession(sid: string, tokens: TokenResponse, after401: boolean): Session {
  return {
    sid,
    accessToken: tokens.accessToken,
    accessExpiresAt: Date.now() + tokens.expiresIn * 1000,
    refreshToken: tokens.refreshToken,
    after401,
  };
}

function getAccessToken(): string | null {
  return session()?.accessToken ?? null;
}

function getRefreshToken(): string | null {
  return session()?.refreshToken ?? null;
}

function hasSession(): boolean {
  return session() !== null;
}

/** The access token is valid for at least the refresh margin. */
function isFresh(s: Session): boolean {
  return s.accessExpiresAt - Date.now() >= REFRESH_MARGIN_MS;
}

/** True when a request should refresh first: there is a session and its access token is about to expire. */
function needsRefresh(): boolean {
  const s = session();
  return s !== null && !isFresh(s);
}

/** A sign-in in THIS tab (login, register): a new sid, so the other tabs load the new user. */
function startSession(tokens: TokenResponse): void {
  current = toSession(newIdempotencyKey(), tokens, false);
  write(current);
  emit({ type: 'started', external: false });
}

/**
 * Stores a rotated pair, but only while storage still holds the sign-in that was refreshed: if another tab signed
 * out, or signed someone in, in the meantime, the stale result is dropped. @return whether it was stored.
 */
function saveRotation(from: Session, tokens: TokenResponse, after401: boolean): boolean {
  const latest = session();
  if (!latest || latest.sid !== from.sid || latest.refreshToken !== from.refreshToken) {
    return false;
  }
  current = toSession(from.sid, tokens, after401);
  write(current);
  return true;
}

/**
 * Forgets the session, tells this tab's listeners why and leaves a tombstone for the other tabs. With `sid`,
 * only if that sign-in is still current: a refusal for an old sign-in must not end a newer one.
 */
function endSession(reason: SessionEndReason, sid?: string): void {
  const latest = session();
  if (!latest || (sid !== undefined && latest.sid !== sid)) {
    return;
  }
  current = null;
  write({ ended: reason, at: Date.now() });
  emit({ type: 'ended', external: false, reason });
}

/** F3: called by the API client after a 401 was fixed by a refresh. */
function markScopesStale(): void {
  emit({ type: 'scopes-stale', external: false });
}

/** Drops the session without telling anyone (tests, and resetting a broken state). */
function clear(): void {
  current = null;
  try {
    storage()?.removeItem(SESSION_KEY);
  } catch {
    // ignore: nothing to remove
  }
}

function onSessionEvent(listener: (event: SessionEvent) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function onSessionEnded(listener: (reason: SessionEndReason, external: boolean) => void): () => void {
  return onSessionEvent((event) => {
    if (event.type === 'ended') {
      listener(event.reason, event.external);
    }
  });
}

// This tab starts in the browser's session, if there is one (a reload, a new or a duplicated tab): no event.
current = (() => {
  const stored = readStored();
  return stored && isSession(stored) ? stored : null;
})();

// Another tab changed the shared session (key null: it cleared the whole storage): re-read and announce it.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event: StorageEvent) => {
    if (event.key === null || event.key === SESSION_KEY) {
      session();
    }
  });
}

export const tokenStore = {
  session,
  isFresh,
  getAccessToken,
  getRefreshToken,
  hasSession,
  needsRefresh,
  startSession,
  saveRotation,
  endSession,
  markScopesStale,
  clear,
  onSessionEvent,
  onSessionEnded,
};
