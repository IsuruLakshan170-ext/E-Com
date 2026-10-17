import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { PageLoading } from '../components/ui/Spinner';
import { ForbiddenPage } from '../pages/ErrorPages';
import { useAuth } from './AuthContext';
import { SessionUnavailable } from './SessionUnavailable';

/** Signed-in users only; others go to sign-in and come back here afterwards. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'loading') {
    return <PageLoading label="Checking your session…" />;
  }
  if (status === 'anonymous') {
    const next = encodeURIComponent(`${location.pathname}${location.search}`);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  if (status === 'unavailable') {
    return <SessionUnavailable />; // F2: still signed in, the server just can't be reached
  }
  return <>{children}</>;
}

/** At least one of the scopes. UX only: the backend enforces every scope again. */
export function RequireScope({ anyOf, children }: { anyOf: readonly string[]; children: ReactNode }) {
  const { hasAnyScope } = useAuth();
  return hasAnyScope(anyOf) ? <>{children}</> : <ForbiddenPage />;
}
