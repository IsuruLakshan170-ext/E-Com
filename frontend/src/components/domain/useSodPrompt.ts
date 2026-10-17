import { useCallback } from 'react';
import { useDialogs } from '../ui/Dialogs';
import type { AskReason } from './sod';

/** The reason dialog used by withSodOverride. */
export function useSodPrompt(): AskReason {
  const { prompt } = useDialogs();
  return useCallback(
    (message: string) =>
      prompt({
        title: 'Reason required',
        message: `${message} The reason is recorded in the audit log.`,
        label: 'Why are you doing this step yourself?',
        confirmLabel: 'Continue',
        maxLength: 300,
        multiline: true,
      }),
    [prompt],
  );
}
