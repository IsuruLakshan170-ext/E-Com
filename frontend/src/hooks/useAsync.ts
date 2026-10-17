import { type DependencyList, useCallback, useEffect, useState } from 'react';

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
  setData: (data: T) => void;
}

interface InternalState<T> {
  data?: T;
  error?: unknown;
  loading: boolean;
}

/**
 * Loads data when the dependencies change and aborts the request when they change again or the component
 * unmounts (no stale responses overwrite newer ones). Previous data stays visible while reloading.
 */
export function useAsync<T>(load: (signal: AbortSignal) => Promise<T>, deps: DependencyList): AsyncState<T> {
  const [state, setState] = useState<InternalState<T>>({ loading: true });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState((previous) => ({ data: previous.data, loading: true }));
    load(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setState({ data, loading: false });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setState((previous) => ({ data: previous.data, error, loading: false }));
      },
    );
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the caller owns the dependency list
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback((data: T) => setState({ data, loading: false }), []);
  return { data: state.data, error: state.error, loading: state.loading, reload, setData };
}
