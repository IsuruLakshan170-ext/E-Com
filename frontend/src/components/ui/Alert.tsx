import type { ReactNode } from 'react';
import { ApiError, errorMessage } from '../../api/errors';

type Tone = 'info' | 'success' | 'warning' | 'danger';

export function Alert({ tone = 'info', title, children }: { tone?: Tone; title?: string; children?: ReactNode }) {
  return (
    <div className={`alert alert-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      {title && <strong className="alert-title">{title}</strong>}
      {children && <div className="alert-body">{children}</div>}
    </div>
  );
}

/** Renders any error; for API problems shows field errors, a retry hint and the request id for support. */
export function ProblemAlert({ error, title = 'That did not work' }: { error: unknown; title?: string }) {
  if (!error) {
    return null;
  }
  const problem = error instanceof ApiError ? error : null;
  return (
    <Alert tone="danger" title={title}>
      <p>{errorMessage(error)}</p>
      {problem && problem.fieldErrors.length > 0 && (
        <ul className="alert-list">
          {problem.fieldErrors.map((fe) => (
            <li key={`${fe.field}-${fe.message}`}>
              <code>{fe.field}</code>: {fe.message}
            </li>
          ))}
        </ul>
      )}
      {problem?.retryable && (
        <p className="muted">
          This is temporary
          {problem.retryAfterSeconds ? ` - try again in ${problem.retryAfterSeconds} s.` : ' - please try again.'}
        </p>
      )}
      {problem?.requestId && <p className="muted small">Reference: {problem.requestId}</p>}
    </Alert>
  );
}
