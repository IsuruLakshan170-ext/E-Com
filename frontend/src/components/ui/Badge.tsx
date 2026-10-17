import { humanize } from '../../lib/format';

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

const TONES: Record<string, Tone> = {
  // orders
  PENDING_PAYMENT: 'warning',
  PAID: 'info',
  SHIPPED: 'info',
  DELIVERED: 'success',
  CANCELLED: 'neutral',
  // returns
  REQUESTED: 'warning',
  APPROVED: 'info',
  REJECTED: 'neutral',
  RECEIVED: 'info',
  REFUNDED: 'success',
  // purchase orders
  DRAFT: 'neutral',
  SUBMITTED: 'warning',
  PARTIALLY_RECEIVED: 'info',
  // payments
  APPLIED: 'success',
  APPLIED_AFTER_REINSTATEMENT: 'success',
  REFUNDED_ORDER_EXPIRED: 'warning',
  REFUNDED_ORDER_CANCELLED: 'warning',
};

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: string }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

/** A status constant rendered with a consistent colour everywhere. */
export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={TONES[status] ?? 'neutral'}>{humanize(status)}</Badge>;
}
