import { type ReactNode, createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { accountApi, authApi } from '../api/endpoints';
import type { ActorType, UserProfile } from '../api/types';
import { type SessionEndReason, tokenStore } from './tokenStore';

/** 'unavailable' (F2): there is a session, but the server could not be reached; it is kept and can be retried. */
export type AuthStatus = 'loading' | 'anonymous' | 'authenticated' | 'unavailable';

export interface SessionUser {
  profile: UserProfile;
  scopes: ReadonlySet<string>;
  actorType: ActorType;
}

interface AuthContextValue {
  status: AuthStatus;
  user: SessionUser | null;
  /** Why the last session ended (shown once on the sign-in page). */
  notice: string | null;
  clearNotice: () => void;
  /** With status 'unavailable': what went wrong (offline, rate-limited, server error). */
  unavailableError: unknown;
  /** Try to resume the session again (status 'unavailable'). */
  retry: () => Promise<void>;
  hasScope: (scope: string) => boolean;
  hasAnyScope: (scopes: readonly string[]) => boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, fullName: string) => Promise<void>;
  logout: () => Promise<void>;
  /** After a password change or "sign out everywhere": the server already revoked everything. */
  endSession: (reason: SessionEndReason) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const NOTICES: Record<SessionEndReason, string | null> = {
  logout: null,
  expired: 'Your session has ended. Please sign in again.',
  revoked: 'You have been signed out everywhere. Please sign in again.',
};

/** The same, when it happened in another tab (F1: every tab shares one session). */
const ELSEWHERE_NOTICES: Record<SessionEndReason, string> = {
  logout: 'You signed out in another tab.',
  expired: 'Your session has ended. Please sign in again.',
  revoked: 'You have been signed out everywhere. Please sign in again.',
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<SessionUser | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [unavailableError, setUnavailableError] = useState<unknown>(null);
  const scopesReload = useRef<Promise<void> | null>(null);

  const loadUser = useCallback(async () => {
    const [who, profile] = await Promise.all([authApi.whoAmI(), accountApi.profile()]);
    setUser({ profile, scopes: new Set(who.scopes), actorType: who.actorType });
    setUnavailableError(null);
    setStatus('authenticated');
  }, []);

  /**
   * Joins the browser's session: the one kept from before a reload, or another tab's. F2: if the server cannot be
   * reached the session is KEPT and the person gets a retry, instead of being signed out.
   */
  const resume = useCallback(async () => {
    if (!tokenStore.hasSession()) {
      setUser(null);
      setStatus('anonymous');
      return;
    }
    try {
      await loadUser(); // the API client refreshes first if the access token is about to expire
    } catch (error) {
      if (tokenStore.hasSession()) {
        setUnavailableError(error);
        setStatus('unavailable');
      } else {
        setUser(null);
        setStatus('anonymous'); // the refresh was refused; the 'ended' event already set the notice
      }
    }
  }, [loadUser]);

  /** F3: re-read the scopes (role changes bump the token version). One reload at a time, so it can never loop. */
  const reloadScopes = useCallback(() => {
    if (scopesReload.current) {
      return;
    }
    scopesReload.current = authApi
      .whoAmI()
      .then(
        (who) => setUser((u) => (u ? { ...u, scopes: new Set(who.scopes), actorType: who.actorType } : u)),
        () => undefined, // keep the old scopes: navigation is UX only, the backend enforces them anyway
      )
      .finally(() => {
        scopesReload.current = null;
      });
  }, []);

  useEffect(() => {
    void resume();
  }, [resume]);

  // F2: while unavailable, try again as soon as the browser reports it is back online.
  useEffect(() => {
    if (status !== 'unavailable') {
      return undefined;
    }
    const onOnline = () => void resume();
    window.addEventListener('online', onOnline);
    return () => window.removeEventListener('online', onOnline);
  }, [status, resume]);

  // F1/F3: follow the shared session (this tab's own changes and those made in other tabs).
  useEffect(
    () =>
      tokenStore.onSessionEvent((event) => {
        if (event.type === 'ended') {
          setUser(null);
          setUnavailableError(null);
          setStatus('anonymous');
          setNotice(event.external ? ELSEWHERE_NOTICES[event.reason] : NOTICES[event.reason]);
        } else if (event.type === 'started' && event.external) {
          setNotice(null);
          void resume(); // signed in (possibly as someone else) in another tab
        } else if (event.type === 'scopes-stale') {
          reloadScopes();
        }
      }),
    [resume, reloadScopes],
  );

  const login = useCallback(
    async (email: string, password: string) => {
      tokenStore.startSession(await authApi.login({ email: email.trim(), password }));
      setNotice(null);
      await loadUser();
    },
    [loadUser],
  );

  const register = useCallback(
    async (email: string, password: string, fullName: string) => {
      tokenStore.startSession(await authApi.register({ email: email.trim(), password, fullName: fullName.trim() }));
      setNotice(null);
      await loadUser();
    },
    [loadUser],
  );

  const logout = useCallback(async () => {
    const refreshToken = tokenStore.getRefreshToken();
    try {
      if (refreshToken) {
        await authApi.logout(refreshToken);
      }
    } catch {
      // the local session is dropped regardless
    } finally {
      tokenStore.endSession('logout'); // every other tab follows (storage event)
      setUser(null);
      setStatus('anonymous');
    }
  }, []);

  const endSession = useCallback((reason: SessionEndReason) => {
    tokenStore.endSession(reason);
    setUser(null);
    setStatus('anonymous');
    setNotice(NOTICES[reason]);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      notice,
      clearNotice: () => setNotice(null),
      unavailableError,
      retry: resume,
      hasScope: (scope) => user?.scopes.has(scope) ?? false,
      hasAnyScope: (scopes) => scopes.length === 0 || scopes.some((scope) => user?.scopes.has(scope) ?? false),
      login,
      register,
      logout,
      endSession,
    }),
    [status, user, notice, unavailableError, resume, login, register, logout, endSession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
