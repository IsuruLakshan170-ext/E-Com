import { type FormEvent, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ordersApi, returnsApi } from '../../api/endpoints';
import { errorMessage } from '../../api/errors';
import type { Order } from '../../api/types';
import { AddressBlock, OrderLines, Totals } from '../../components/domain/OrderSummary';
import { Alert, ProblemAlert } from '../../components/ui/Alert';
import { StatusBadge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { useDialogs } from '../../components/ui/Dialogs';
import { Field, FormActions } from '../../components/ui/Field';
import { Card, KeyValueList, PageHeader } from '../../components/ui/Layout';
import { PageLoading } from '../../components/ui/Spinner';
import { useToasts } from '../../components/ui/Toasts';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle, useIdempotencyKey } from '../../hooks/misc';
import { formatDateTime, humanize } from '../../lib/format';

export function MyOrderPage() {
  const { id = '' } = useParams();
  const location = useLocation();
  const justPlaced = (location.state as { placed?: boolean } | null)?.placed === true;
  const order = useAsync((signal) => ordersApi.getMine(id, signal), [id]);
  const { confirm } = useDialogs();
  const toasts = useToasts();
  const [showReturn, setShowReturn] = useState(false);
  useDocumentTitle(order.data ? `Order ${order.data.orderNumber}` : 'Order');

  if (order.loading && !order.data) return <PageLoading />;
  if (!order.data) return <ProblemAlert error={order.error} title="Order not found" />;
  const o = order.data;

  const onCancel = async () => {
    const ok = await confirm({ title: 'Cancel this order?', message: 'The items go back on sale. This cannot be undone.', confirmLabel: 'Cancel order', danger: true });
    if (!ok) return;
    try {
      order.setData(await ordersApi.cancelMine(o.id));
      toasts.success('Order cancelled');
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  const onApprove = async () => {
    try {
      order.setData(await ordersApi.approve(o.id));
      toasts.success('Order approved; it can now be paid');
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  const onReject = async () => {
    const ok = await confirm({ title: 'Reject this order?', message: 'It is cancelled and its stock released. This cannot be undone.', confirmLabel: 'Reject order', danger: true });
    if (!ok) return;
    try {
      order.setData(await ordersApi.reject(o.id));
      toasts.success('Order rejected and cancelled');
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  return (
    <div className="page">
      <PageHeader
        title={`Order ${o.orderNumber}`}
        subtitle={<StatusBadge status={o.status} />}
        actions={
          <>
            {o.allowedActions.includes('approve') && (
              <Button variant="primary" onClick={onApprove}>
                Approve
              </Button>
            )}
            {o.allowedActions.includes('reject') && (
              <Button variant="danger" onClick={onReject}>
                Reject
              </Button>
            )}
            {o.allowedActions.includes('viewInvoice') && (
              <Link className="btn btn-secondary" to={`/account/orders/${o.id}/invoice`}>
                Invoice
              </Link>
            )}
            {o.allowedActions.includes('requestReturn') && (
              <Button onClick={() => setShowReturn(true)}>Return items</Button>
            )}
            {o.allowedActions.includes('cancel') && (
              <Button variant="danger" onClick={onCancel}>
                Cancel order
              </Button>
            )}
          </>
        }
      />
      {justPlaced && (
        <Alert tone="success" title="Thank you, your order is placed">
          Your items are reserved until {formatDateTime(o.expiresAt)}. Please pay before then using your order number
          ({o.orderNumber}) as the payment reference; we confirm the payment and then ship.
        </Alert>
      )}
      {o.approvalState === 'PENDING' && (
        <Alert tone="warning" title="Waiting for your approval">
          This order was placed by your agent and is above its key's approval threshold. It can't be paid until you
          approve or reject it; it's reserved in the meantime.
        </Alert>
      )}
      {o.approvalState === 'APPROVED' && o.status === 'PENDING_PAYMENT' && (
        <Alert tone="info">You approved this order on {formatDateTime(o.approvalDecidedAt)}. It can now be paid.</Alert>
      )}
      {o.status === 'PENDING_PAYMENT' && !justPlaced && o.expiresAt && o.approvalState !== 'PENDING' && (
        <Alert tone="warning">Awaiting payment. Unpaid orders are released on {formatDateTime(o.expiresAt)}.</Alert>
      )}
      {o.status === 'CANCELLED' && (
        <Alert tone="info">This order was cancelled{o.cancelReason ? ` (${humanize(o.cancelReason)})` : ''}. Any payment has been refunded.</Alert>
      )}
      <div className="grid-2">
        <Card title="Items">
          <OrderLines lines={o.items} />
          <Totals subtotal={o.subtotal} taxRateBasisPoints={o.taxRateBasisPoints} tax={o.tax} total={o.total} refunded={o.refunded} />
        </Card>
        <div className="stack">
          <Card title="Delivery">
            <AddressBlock address={o.shippingAddress} />
          </Card>
          <Card title="Timeline">
            <KeyValueList
              items={[
                { label: 'Placed', value: formatDateTime(o.createdAt) },
                { label: 'Paid', value: formatDateTime(o.paidAt) },
                { label: 'Shipped', value: formatDateTime(o.shippedAt) },
                { label: 'Delivered', value: formatDateTime(o.deliveredAt) },
              ]}
            />
          </Card>
        </div>
      </div>
      {showReturn && <ReturnForm order={o} onClose={() => setShowReturn(false)} />}
    </div>
  );
}

function ReturnForm({ order, onClose }: { order: Order; onClose: () => void }) {
  const navigate = useNavigate();
  const idem = useIdempotencyKey();
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const lines = order.items.filter((l) => (quantities[l.productId] ?? 0) > 0).map((l) => ({ productId: l.productId, quantity: quantities[l.productId] }));

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (lines.length === 0 || !reason.trim()) {
      setError(new Error('Choose at least one item and tell us why.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await returnsApi.request(order.id, { reason: reason.trim(), lines }, idem.key);
      navigate(`/account/returns/${result.data.id}`, { state: { created: true } });
    } catch (e) {
      setError(e); // a retry reuses the same idempotency key: one return, however often the button is pressed
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Return items" actions={<Button variant="ghost" onClick={onClose}>Close</Button>}>
      <form onSubmit={onSubmit}>
        {order.items.map((line) => (
          <Field key={line.productId} label={`${line.productName} (bought ${line.quantity})`}>
            <input
              type="number"
              min={0}
              max={line.quantity}
              value={quantities[line.productId] ?? 0}
              onChange={(e) => {
                setQuantities({ ...quantities, [line.productId]: Math.max(0, Math.min(line.quantity, Number(e.target.value) || 0)) });
                idem.renew(); // a different request needs a different key
              }}
            />
          </Field>
        ))}
        <Field label="Reason" required>
          <textarea
            value={reason}
            maxLength={500}
            rows={3}
            onChange={(e) => {
              setReason(e.target.value);
              idem.renew();
            }}
          />
        </Field>
        <ProblemAlert error={error} title="Return not requested" />
        <FormActions>
          <Button type="submit" variant="primary" busy={busy}>
            Request return
          </Button>
        </FormActions>
      </form>
    </Card>
  );
}
