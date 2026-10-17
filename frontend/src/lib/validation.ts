// Client-side mirrors of the backend rules, for instant feedback. The server stays the authority.

export const SKU_PATTERN = /^[A-Z0-9][A-Z0-9_-]{2,63}$/;
/** Same-origin image path; mirrors the backend's IMAGE_URL_REGEX (no "//host", no ".."). */
export const IMAGE_PATH_PATTERN = /^\/(?!\/)(?!.*\.\.)[A-Za-z0-9_./-]{1,299}$/;
export const SUPPLIER_CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{1,31}$/;
export const COUNTRY_PATTERN = /^[A-Z]{2}$/;
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** An order-number search (BE-2), same rule as the backend's OrderNumbers.PARAM_PATTERN; matched case-insensitively. */
export const ORDER_NUMBER_PATTERN = /^[A-Za-z0-9-]{1,32}$/;
export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_BYTES = 72;

/** Mirrors PasswordPolicy: at least 12 characters, at most 72 bytes (BCrypt), not the email. */
export function passwordProblem(password: string, email?: string): string | null {
  if (password.trim().length < PASSWORD_MIN_LENGTH) {
    return `Use at least ${PASSWORD_MIN_LENGTH} characters.`;
  }
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    return `Use at most ${PASSWORD_MAX_BYTES} bytes (shorter, or fewer special characters).`;
  }
  if (email && password.toLowerCase() === email.trim().toLowerCase()) {
    return 'The password must not be your email address.';
  }
  return null;
}

export function blankToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

// F4: URL parsers strip tabs and newlines, so "/<TAB>/evil.example" becomes "//evil.example". Real paths never
// contain whitespace or control characters raw (they arrive percent-encoded).
const UNSAFE_PATH_CHARS = /[\s\u0000-\u001f\u007f-\u009f]/;

/**
 * Same-site relative path only; anything else (absolute URLs, //host, /\host, whitespace or control characters)
 * falls back. Prevents open redirects.
 */
export function safeNextPath(next: string | null | undefined, fallback = '/'): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\') || UNSAFE_PATH_CHARS.test(next)) {
    return fallback;
  }
  return next;
}
