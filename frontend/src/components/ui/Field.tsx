import { type ReactNode, useId } from 'react';

interface FieldProps {
  label: string;
  children: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  required?: boolean;
  className?: string;
  /** A composite control (a type-ahead with an input and buttons): a labelled group, since a <label> may hold one control only. */
  group?: boolean;
}

/** Label wraps the control, so they are associated without ids (or a labelled group, see `group`). */
export function Field({ label, children, hint, error, required, className, group = false }: FieldProps) {
  const labelId = useId();
  const classes = ['field', error ? 'field-invalid' : '', className ?? ''].filter(Boolean).join(' ');
  const content = (
    <>
      <span className="field-label" id={group ? labelId : undefined}>
        {label}
        {required && <span className="field-required" aria-hidden="true"> *</span>}
      </span>
      {children}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </>
  );
  return group ? (
    <div className={classes} role="group" aria-labelledby={labelId}>
      {content}
    </div>
  ) : (
    <label className={classes}>{content}</label>
  );
}

export function FormRow({ children }: { children: ReactNode }) {
  return <div className="form-row">{children}</div>;
}

export function FormActions({ children }: { children: ReactNode }) {
  return <div className="form-actions">{children}</div>;
}
