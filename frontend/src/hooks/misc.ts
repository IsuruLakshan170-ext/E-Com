import { useEffect, useRef, useState } from 'react';
import { newIdempotencyKey } from '../api/idempotency';

export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = title ? `${title} · E-Com` : 'E-Com';
  }, [title]);
}

export function useDebounced<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

/**
 * One idempotency key per user intention: stable across retries of the same request, renewed explicitly when the
 * request body changes or after success.
 */
export function useIdempotencyKey(): { key: string; renew: () => void } {
  const ref = useRef<string>(newIdempotencyKey());
  const [, force] = useState(0);
  return {
    key: ref.current,
    renew: () => {
      ref.current = newIdempotencyKey();
      force((n) => n + 1);
    },
  };
}
