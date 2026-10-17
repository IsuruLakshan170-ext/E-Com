import type { ReactNode } from 'react';

export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

export function Card({ title, actions, children, className }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={['card', className ?? ''].filter(Boolean).join(' ')}>
      {(title || actions) && (
        <div className="card-header">
          {title && <h2 className="card-title">{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </div>
      )}
      <div className="card-body">{children}</div>
    </section>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty-state">
      <p className="empty-title">{title}</p>
      {children && <div className="empty-body">{children}</div>}
    </div>
  );
}

export interface KeyValueItem {
  label: string;
  value: ReactNode;
}

export function KeyValueList({ items }: { items: KeyValueItem[] }) {
  return (
    <dl className="kv">
      {items.map((item) => (
        <div className="kv-row" key={item.label}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  );
}
