import { useCallback, useRef, useState } from 'react';

export interface Action<A extends unknown[], R> {
  run: (...args: A) => Promise<R | undefined>;
  pending: boolean;
  error: unknown;
  clearError: () => void;
}

/**
 * Wraps a user action (submit, approve, ...): tracks pending state, keeps the last error for display, and
 * ignores double clicks while a call is in flight.
 */
export function useAction<A extends unknown[], R>(fn: (...args: A) => Promise<R>): Action<A, R> {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const inFlight = useRef(false);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const run = useCallback(async (...args: A): Promise<R | undefined> => {
    if (inFlight.current) {
      return undefined;
    }
    inFlight.current = true;
    setPending(true);
    setError(null);
    try {
      return await fnRef.current(...args);
    } catch (e) {
      setError(e);
      return undefined;
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  }, []);

  const clearError = useCallback(() => setError(null), []);
  return { run, pending, error, clearError };
}
