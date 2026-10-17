import { type ReactNode, createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { Button } from './Button';
import { Field } from './Field';
import { Modal } from './Modal';

interface ConfirmOptions {
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
}

interface PromptOptions {
  title: string;
  message?: ReactNode;
  label: string;
  confirmLabel?: string;
  maxLength?: number;
  required?: boolean;
  multiline?: boolean;
}

interface DialogsValue {
  confirm: (options: ConfirmOptions) => Promise<boolean>;
  prompt: (options: PromptOptions) => Promise<string | null>;
}

type Pending =
  | { kind: 'confirm'; options: ConfirmOptions; resolve: (ok: boolean) => void }
  | { kind: 'prompt'; options: PromptOptions; resolve: (value: string | null) => void };

const DialogsContext = createContext<DialogsValue | null>(null);

/** Promise-based confirm/prompt dialogs (instead of window.confirm), used e.g. for separation-of-duties reasons. */
export function DialogProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const [text, setText] = useState('');
  const pendingRef = useRef<Pending | null>(null);
  pendingRef.current = pending;

  const confirm = useCallback(
    (options: ConfirmOptions) => new Promise<boolean>((resolve) => setPending({ kind: 'confirm', options, resolve })),
    [],
  );
  const prompt = useCallback(
    (options: PromptOptions) =>
      new Promise<string | null>((resolve) => {
        setText('');
        setPending({ kind: 'prompt', options, resolve });
      }),
    [],
  );

  const close = useCallback(() => {
    const current = pendingRef.current;
    if (current?.kind === 'confirm') current.resolve(false);
    if (current?.kind === 'prompt') current.resolve(null);
    setPending(null);
  }, []);

  const accept = () => {
    if (pending?.kind === 'confirm') {
      pending.resolve(true);
      setPending(null);
    } else if (pending?.kind === 'prompt') {
      const value = text.trim();
      if (pending.options.required !== false && !value) return;
      pending.resolve(value);
      setPending(null);
    }
  };

  const value = useMemo(() => ({ confirm, prompt }), [confirm, prompt]);
  const options = pending?.options;
  const promptOptions = pending?.kind === 'prompt' ? pending.options : null;

  return (
    <DialogsContext.Provider value={value}>
      {children}
      <Modal
        open={pending !== null}
        title={options?.title ?? ''}
        onClose={close}
        footer={
          <>
            <Button variant="ghost" onClick={close}>
              Cancel
            </Button>
            <Button
              variant={pending?.kind === 'confirm' && pending.options.danger ? 'danger' : 'primary'}
              onClick={accept}
              disabled={promptOptions !== null && promptOptions.required !== false && !text.trim()}
            >
              {options?.confirmLabel ?? 'Confirm'}
            </Button>
          </>
        }
      >
        {options?.message && <div className="dialog-message">{options.message}</div>}
        {promptOptions && (
          <Field label={promptOptions.label} hint={promptOptions.maxLength ? `Up to ${promptOptions.maxLength} characters` : undefined}>
            {promptOptions.multiline ? (
              <textarea value={text} maxLength={promptOptions.maxLength} rows={3} onChange={(e) => setText(e.target.value)} />
            ) : (
              <input value={text} maxLength={promptOptions.maxLength} onChange={(e) => setText(e.target.value)} />
            )}
          </Field>
        )}
      </Modal>
    </DialogsContext.Provider>
  );
}

export function useDialogs(): DialogsValue {
  const context = useContext(DialogsContext);
  if (!context) {
    throw new Error('useDialogs must be used inside <DialogProvider>');
  }
  return context;
}
