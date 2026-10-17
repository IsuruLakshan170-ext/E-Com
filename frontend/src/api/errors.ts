export interface FieldError {
  field: string;
  message: string;
}

interface ApiErrorInit {
  status: number;
  code: string;
  message: string;
  retryable?: boolean;
  requestId?: string;
  fieldErrors?: FieldError[];
  details?: Record<string, unknown>;
  retryAfterSeconds?: number;
}

const STANDARD_PROBLEM_FIELDS = new Set(['type', 'title', 'status', 'detail', 'instance', 'code', 'retryable', 'requestId', 'errors']);

/**
 * An RFC 7807 problem returned by the backend: a stable machine-readable `code`, a `retryable` flag, the request
 * id for support, field-level validation errors, and any extra structured details (e.g. `available`,
 * `actualTotalMinor`, `overrideAllowed`). UI code branches on `code`, never on the message text.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryable: boolean;
  readonly requestId?: string;
  readonly fieldErrors: FieldError[];
  readonly details: Record<string, unknown>;
  readonly retryAfterSeconds?: number;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.retryable = init.retryable ?? false;
    this.requestId = init.requestId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.details = init.details ?? {};
    this.retryAfterSeconds = init.retryAfterSeconds;
  }

  is(...codes: string[]): boolean {
    return codes.includes(this.code);
  }

  static fromResponse(status: number, body: unknown, retryAfterHeader: string | null): ApiError {
    const problem: Record<string, unknown> = body !== null && typeof body === 'object' ? (body as Record<string, unknown>) : {};
    const details: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(problem)) {
      if (!STANDARD_PROBLEM_FIELDS.has(key)) {
        details[key] = value;
      }
    }
    const retryAfter = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : Number.NaN;
    return new ApiError({
      status,
      code: typeof problem.code === 'string' ? problem.code : fallbackCode(status),
      message: typeof problem.detail === 'string' && problem.detail ? problem.detail : fallbackMessage(status),
      retryable: problem.retryable === true,
      requestId: typeof problem.requestId === 'string' ? problem.requestId : undefined,
      fieldErrors: Array.isArray(problem.errors) ? problem.errors.filter(isFieldError) : [],
      details,
      retryAfterSeconds: Number.isFinite(retryAfter) ? retryAfter : undefined,
    });
  }

  static network(): ApiError {
    return new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'Cannot reach the server. Check your connection and try again.',
      retryable: true,
    });
  }
}

function isFieldError(value: unknown): value is FieldError {
  return value !== null && typeof value === 'object'
    && typeof (value as FieldError).field === 'string' && typeof (value as FieldError).message === 'string';
}

function fallbackCode(status: number): string {
  if (status === 401) return 'UNAUTHENTICATED';
  if (status === 403) return 'FORBIDDEN';
  if (status === 404) return 'NOT_FOUND';
  if (status === 409) return 'CONFLICT';
  if (status === 429) return 'RATE_LIMITED';
  if (status >= 500) return 'INTERNAL_ERROR';
  return `HTTP_${status}`;
}

function fallbackMessage(status: number): string {
  if (status === 401) return 'Please sign in to continue.';
  if (status === 403) return 'You do not have permission to do that.';
  if (status === 404) return 'Not found.';
  if (status === 429) return 'Too many requests. Please wait a moment and try again.';
  if (status >= 500) return 'Something went wrong on our side. Please try again.';
  return 'The request could not be completed.';
}

export function isApiError(error: unknown, ...codes: string[]): error is ApiError {
  return error instanceof ApiError && (codes.length === 0 || codes.includes(error.code));
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Something went wrong.';
}

/** Field name -> message, for showing server-side validation errors next to inputs. */
export function fieldErrors(error: unknown): Record<string, string> {
  const map: Record<string, string> = {};
  if (error instanceof ApiError) {
    for (const fe of error.fieldErrors) {
      map[fe.field] = fe.message;
    }
  }
  return map;
}
