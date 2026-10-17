import type { ButtonHTMLAttributes } from 'react';
import { Spinner } from './Spinner';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: 'sm' | 'md';
  /** Shows a spinner and disables the button while an action runs. */
  busy?: boolean;
}

export function Button({ variant = 'secondary', size = 'md', busy = false, className, children, disabled, type, ...rest }: ButtonProps) {
  const classes = ['btn', `btn-${variant}`, size === 'sm' ? 'btn-sm' : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <button type={type ?? 'button'} className={classes} disabled={disabled || busy} aria-busy={busy || undefined} {...rest}>
      {busy && <Spinner small />}
      <span>{children}</span>
    </button>
  );
}
