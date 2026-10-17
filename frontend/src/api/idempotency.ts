/**
 * Idempotency keys make retries safe: the backend remembers (user, operation, key) and replays the original
 * result instead of doing the work twice. Rule of thumb for the UI: create ONE key per user intention (e.g. when
 * the review step of checkout opens), reuse it for every retry of that same request, and create a new one only
 * when the request body changes or after success.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // Fallback for non-secure contexts: RFC 4122 v4 from getRandomValues.
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
