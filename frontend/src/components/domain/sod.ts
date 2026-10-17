import { ApiError } from '../../api/errors';

export type AskReason = (message: string) => Promise<string | null>;

/**
 * Separation-of-duties helper (backend R10). In small-team mode a step that one person may not normally do
 * returns 403 SEGREGATION_OF_DUTIES with overrideAllowed=true; we then ask for a reason and retry with it (the
 * backend audits it as SOD_OVERRIDDEN). In strict mode, or for conflicts of interest, the error is passed on.
 *
 * @return the action's result, or null if the person cancelled the reason dialog
 */
export async function withSodOverride<T>(action: (reason?: string) => Promise<T>, askReason: AskReason): Promise<T | null> {
  try {
    return await action(undefined);
  } catch (error) {
    if (error instanceof ApiError && error.code === 'SEGREGATION_OF_DUTIES' && error.details.overrideAllowed === true) {
      const reason = await askReason(error.message);
      if (!reason) {
        return null;
      }
      return action(reason);
    }
    throw error;
  }
}
