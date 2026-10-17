import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/errors';
import { withSodOverride } from './sod';

const sodError = (overrideAllowed: boolean) =>
  new ApiError({ status: 403, code: 'SEGREGATION_OF_DUTIES', message: 'Approve and receive must be different people', details: overrideAllowed ? { overrideAllowed: true } : {} });

describe('withSodOverride (small-team mode)', () => {
  it('does not ask when the step is allowed', async () => {
    const ask = vi.fn();
    expect(await withSodOverride(async () => 'ok', ask)).toBe('ok');
    expect(ask).not.toHaveBeenCalled();
  });

  it('asks for a reason and retries with it when an override is allowed', async () => {
    const action = vi.fn(async (reason?: string) => {
      if (!reason) throw sodError(true);
      return `done: ${reason}`;
    });
    const result = await withSodOverride(action, async () => 'One-person shop');
    expect(result).toBe('done: One-person shop');
    expect(action).toHaveBeenLastCalledWith('One-person shop');
  });

  it('returns null when the person cancels', async () => {
    expect(await withSodOverride(async () => Promise.reject(sodError(true)), async () => null)).toBeNull();
  });

  it('passes on hard blocks (strict mode or conflict of interest)', async () => {
    await expect(withSodOverride(async () => Promise.reject(sodError(false)), async () => 'reason')).rejects.toMatchObject({ code: 'SEGREGATION_OF_DUTIES' });
  });
});
