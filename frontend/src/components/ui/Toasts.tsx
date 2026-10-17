import { type ReactNode, createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';

type ToastTone = 'success' | 'danger' | 'info';

interface Toast {
  id: number;
  tone: ToastTone;
  message: string;
}

interface ToastsValue {
  success: (message: string) => void;
  error: (message: string) => void;
  info: (message: string) => void;
}

const ToastsContext = createContext<ToastsValue | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const push = useCallback((tone: ToastTone, message: string) => {
    const id = nextId.current++;
    setToasts((current) => [...current.slice(-3), { id, tone, message }]);
    window.setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), tone === 'danger' ? 8000 : 4500);
  }, []);

  const value = useMemo<ToastsValue>(
    () => ({
      success: (message) => push('success', message),
      error: (message) => push('danger', message),
      info: (message) => push('info', message),
    }),
    [push],
  );

  return (
    <ToastsContext.Provider value={value}>
      {children}
      <div className="toasts" aria-live="polite" aria-atomic="false">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast-${toast.tone}`} role={toast.tone === 'danger' ? 'alert' : 'status'}>
            <span>{toast.message}</span>
            <button
              type="button"
              className="toast-close"
              aria-label="Dismiss"
              onClick={() => setToasts((current) => current.filter((t) => t.id !== toast.id))}
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastsContext.Provider>
  );
}

export function useToasts(): ToastsValue {
  const context = useContext(ToastsContext);
  if (!context) {
    throw new Error('useToasts must be used inside <ToastProvider>');
  }
  return context;
}
