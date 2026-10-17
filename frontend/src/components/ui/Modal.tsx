import { type ReactNode, useEffect, useRef } from 'react';

interface ModalProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}

/** Accessible dialog: role=dialog, Escape closes, focus moves into the dialog and back on close. */
export function Modal({ open, title, onClose, children, footer }: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  // Keep the latest onClose without re-running the focus effect on every parent render
  // (an inline onClose would otherwise steal focus from inputs while the person types).
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const focusable = dialogRef.current?.querySelector<HTMLElement>('input, textarea, select, button, [href]');
    focusable?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previouslyFocused?.focus();
    };
  }, [open]);

  if (!open) {
    return null;
  }
  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" ref={dialogRef}>
        <h2 id="modal-title" className="modal-title">
          {title}
        </h2>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}
