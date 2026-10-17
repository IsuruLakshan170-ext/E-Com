import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ordersApi } from '../../../api/endpoints';
import { errorMessage } from '../../../api/errors';
import type { OrderStatus } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { AddressBlock, OrderLines, Totals } from '../../../components/domain/OrderSummary';
import { Alert, ProblemAlert } from '../../../components/ui/Alert';
import { StatusBadge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { useDialogs } from '../../../components/ui/Dialogs';
import { Card, KeyValueList, PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize } from '../../../lib/format';
import { SCOPES } from '../../../lib/scopes';

const ACTION_LABEL: Partial<Record<OrderStatus, string>> = {
  SHIPPED: 'Mark as shipped',
  DELIVERED: 'Mark as delivered',
  CANCELLED: 'Cancel order',
};

export function AdminOrderPage() {
  const { id = '' } = useParams();
  const order = useAsync((signal) => ordersApi.adminGet(id, signal), [id]);
  const { hasScope } = useAuth();
  const { confirm } = useDialogs();
  const toasts = useToasts();
  const [busy, setBusy] = useState(false);
  useDocumentTitle(order.data ? `Order ${order.data.orderNumber}` : 'Order');

  if (order.loading && !order.data) return <PageLoading />;
  if (!order.data) return <ProblemAlert error={order.error} title="Order not found" />;
  const o = order.data;

  const onTransition = async (next: OrderStatus) => {
    const message =
      next === 'CANCELLED'
        ? o.status === 'PAID'
          ? 'The customer is refunded in full automatically and the reserved stock is released.'
          : 'The reserved stock is released.'
        : next === 'SHIPPED'
          ? 'The reserved units leave stock and revenue is recognised.'
          : 'The customer can then request a return for 30 days.';
    if (!(await confirm({ title: `${ACTION_LABEL[next] ?? humanize(next)}?`, message, confirmLabel: ACTION_LABEL[next] ?? 'Confirm', danger: next === 'CANCELLED' }))) return;
    setBusy(true);
    try {
      order.setData(await ordersApi.adminUpdateStatus(o.id, next));
      toasts.success(`Order ${humanize(next).toLowerCase()}`);
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader
        title={`Order ${o.orderNumber}`}
        subtitle={<StatusBadge status={o.status} />}
        actions={
          <>
            {o.status === 'PENDING_PAYMENT' && hasScope(SCOPES.FINANCE_WRITE) && (
              <Link className="btn btn-primary" to={`/backoffice/finance/payments/new?orderNumber=${encodeURIComponent(o.orderNumber)}`}>
                Record payment
              </Link>
            )}
            {o.allowedNextStatuses.map((next) => (
              <Button key={next} variant={next === 'CANCELLED' ? 'danger' : 'primary'} busy={busy} onClick={() => onTransition(next)}>
                {ACTION_LABEL[next] ?? humanize(next)}
              </Button>
            ))}
          </>
        }
      />
      {o.status === 'PENDING_PAYMENT' && (
        <Alert tone="warning">Awaiting payment. Released automatically on {formatDateTime(o.expiresAt)} if unpaid.</Alert>
      )}
      {o.status === 'CANCELLED' && o.cancelReason && <Alert tone="info">Cancelled ({humanize(o.cancelReason)}).</Alert>}
      <div className="grid-2">
        <Card title="Items">
          <OrderLines lines={o.items} />
          <Totals subtotal={o.subtotal} taxRateBasisPoints={o.taxRateBasisPoints} tax={o.tax} total={o.total} refunded={o.refunded} />
        </Card>
        <div className="stack">
          <Card title="Ship to">
            <AddressBlock address={o.shippingAddress} />
          </Card>
          <Card title="Timeline">
            <KeyValueList
              items={[
                { label: 'Placed', value: formatDateTime(o.createdAt) },
                { label: 'Paid', value: formatDateTime(o.paidAt) },
                { label: 'Shipped', value: formatDateTime(o.shippedAt) },
                { label: 'Delivered', value: formatDateTime(o.deliveredAt) },
                { label: 'Order id', value: <code>{o.id}</code> },
              ]}
            />
          </Card>
        </div>
      </div>
    </div>
  );
}
