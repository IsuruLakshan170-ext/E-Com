import { describe, expect, it } from 'vitest';
import { ApiError, fieldErrors } from './errors';

describe('ApiError.fromResponse', () => {
  it('reads the backend problem+json contract', () => {
    const error = ApiError.fromResponse(
      409,
      {
        type: 'urn:ecom:error:price_changed',
        title: 'Conflict',
        status: 409,
        detail: 'The order total differs from expectedTotalMinor',
        code: 'PRICE_CHANGED',
        retryable: false,
        requestId: 'req-123',
        actualTotalMinor: 5500,
      },
      null,
    );
    expect(error.code).toBe('PRICE_CHANGED');
    expect(error.message).toBe('The order total differs from expectedTotalMinor');
    expect(error.retryable).toBe(false);
    expect(error.requestId).toBe('req-123');
    expect(error.details).toEqual({ actualTotalMinor: 5500 });
  });

  it('keeps field errors and Retry-After', () => {
    const error = ApiError.fromResponse(
      429,
      { code: 'RATE_LIMITED', retryable: true, errors: [{ field: 'email', message: 'must not be blank' }, { bad: true }] },
      '7',
    );
    expect(error.retryable).toBe(true);
    expect(error.retryAfterSeconds).toBe(7);
    expect(fieldErrors(error)).toEqual({ email: 'must not be blank' });
  });

  it('falls back sensibly for non-JSON errors', () => {
    const error = ApiError.fromResponse(502, undefined, null);
    expect(error.code).toBe('INTERNAL_ERROR');
    expect(error.message).toMatch(/try again/i);
  });
});
