import type { TokenResponse } from '../api/types';

/** A JSON response as the backend sends it (problem+json uses the same shape). */
export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

/** A token pair; expiresInSeconds below 30 means "about to expire" (inside the client's refresh margin). */
export function tokens(access: string, refresh: string, expiresInSeconds = 900): TokenResponse {
  return { accessToken: access, tokenType: 'Bearer', expiresIn: expiresInSeconds, refreshToken: refresh, refreshExpiresIn: 1_209_600 };
}
