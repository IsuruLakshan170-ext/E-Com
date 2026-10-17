import { type FormEvent, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { purchasingApi } from '../../../api/endpoints';
import { errorMessage } from '../../../api/errors';
import type { PurchaseOrder } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { withSodOverride } from '../../../components/domain/sod';
import { useSodPrompt } from '../../../components/domain/useSodPrompt';
import { Alert, ProblemAlert } from '../../../components/ui/Alert';
import { StatusBadge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { useDialogs } from '../../../components/ui/Dialogs';
import { Field, FormActions } from '../../../components/ui/Field';
import { Card, KeyValueList, PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle, useIdempotencyKey } from '../../../hooks/misc';
import { formatDate, formatDateTime } from '../../../lib/format';
import { SCOPES } from '../../../lib/scopes';

export function PurchaseOrderPage() {
  const { id = '' } = useParams();
  const po = useAsync((signal) => purchasingApi.getOrder(id, signal), [id]);
  const receipts = useAsync((signal) => purchasingApi.listReceipts(id, signal), [id]);
  const { hasScope } = useAuth();
  const { confirm } = useDialogs();
  const askReason = useSodPrompt();
  const toasts = useToasts();
  const [busy, setBusy] = useState(false);
  useDocumentTitle(po.data?.poNumber ?? 'Purchase order');

  if (po.loading && !po.data) return <PageLoading />;
  if (!po.data) return <ProblemAlert error={po.error} title="Purchase order not found" />;
  const order = po.data;
  const can = (action: string) => order.allowedActions.includes(action);

  const act = async (work: () => Promise<PurchaseOrder | null>, success: string) => {
    setBusy(true);
    try {
      const updated = await work();
      if (updated) {
        po.setData(updated);
        toasts.success(success);
      }
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const onSubmitForApproval = () => act(() => purchasingApi.submit(order.id), 'Submitted for approval');
  const onApprove = () => act(() => withSodOverride((reason) => purchasingApi.approve(order.id, reason), askReason), 'Approved');
  const onCancel = async () => {
    if (await confirm({ title: `Cancel ${order.poNumber}?`, message: 'The supplier order is withdrawn. This cannot be undone.', confirmLabel: 'Cancel purchase order', danger: true })) {
      await act(() => purchasingApi.cancel(order.id), 'Purchase order cancelled');
    }
  };

  return (
    <div className="page">
      <PageHeader
        title={order.poNumber}
        subtitle={<StatusBadge status={order.status} />}
        actions={
          <>
            {can('update') && hasScope(SCOPES.PURCHASING_WRITE) && (
              <Link className="btn btn-secondary" to={`/backoffice/purchasing/orders/${order.id}/edit`}>
                Edit draft
              </Link>
            )}
            {can('submit') && hasScope(SCOPES.PURCHASING_WRITE) && (
              <Button variant="primary" busy={busy} onClick={onSubmitForApproval}>
                Submit for approval
              </Button>
            )}
            {can('approve') && hasScope(SCOPES.PURCHASING_APPROVE) && (
              <Button variant="primary" busy={busy} onClick={onApprove}>
                Approve
              </Button>
            )}
            {can('cancel') && hasScope(SCOPES.PURCHASING_WRITE) && (
              <Button variant="danger" busy={busy} onClick={onCancel}>
                Cancel
              </Button>
            )}
          </>
        }
      />
      <div className="grid-2">
        <Card title="Lines">
          <DataTable
            rows={order.lines}
            rowKey={(l) => String(l.lineNo)}
            columns={[
              { key: 'product', header: 'Product', cell: (l) => <>{l.productName} <span className="muted">({l.sku})</span></> },
              { key: 'ordered', header: 'Ordered', align: 'right', cell: (l) => l.quantityOrdered },
              { key: 'received', header: 'Received', align: 'right', cell: (l) => l.quantityReceived },
              { key: 'cost', header: 'Unit cost', align: 'right', cell: (l) => <MoneyText value={l.unitCost} /> },
              { key: 'total', header: 'Line total', align: 'right', cell: (l) => <MoneyText value={l.lineTotal} /> },
            ]}
          />
          <p className="summary-row">
            <span>Total</span>
            <MoneyText value={order.total} strong />
          </p>
        </Card>
        <Card title="Details">
          <KeyValueList
            items={[
              { label: 'Supplier', value: <Link to={`/backoffice/purchasing/suppliers/${order.supplierId}`}>{order.supplierName}</Link> },
              { label: 'Expected', value: formatDate(order.expectedDate) },
              { label: 'Notes', value: order.notes ?? '—' },
              { label: 'Created', value: formatDateTime(order.createdAt) },
              { label: 'Approved', value: order.approvedAt ? formatDateTime(order.approvedAt) : '—' },
            ]}
          />
        </Card>
      </div>
      {can('receive') && hasScope(SCOPES.INVENTORY_WRITE) && (
        <ReceiveForm order={order} onReceived={() => {
          po.reload();
          receipts.reload();
        }} />
      )}
      <Card title="Deliveries received">
        <ProblemAlert error={receipts.error} />
        <DataTable
          rows={receipts.data ?? []}
          rowKey={(r) => r.id}
          empty="Nothing received yet."
          columns={[
            { key: 'number', header: 'Receipt', cell: (r) => r.receiptNumber },
            { key: 'when', header: 'Received', cell: (r) => formatDateTime(r.receivedAt) },
            { key: 'items', header: 'Items', cell: (r) => r.lines.map((l) => `${l.sku} × ${l.quantity}`).join(', ') },
            { key: 'lines', header: 'Units', align: 'right', cell: (r) => r.lines.reduce((n, l) => n + l.quantity, 0) },
            { key: 'cost', header: 'Cost', align: 'right', cell: (r) => <MoneyText value={r.totalCost} /> },
            { key: 'note', header: 'Note', cell: (r) => r.note ?? '—' },
          ]}
        />
      </Card>
    </div>
  );
}

function ReceiveForm({ order, onReceived }: { order: PurchaseOrder; onReceived: () => void }) {
  const idem = useIdempotencyKey();
  const askReason = useSodPrompt();
  const toasts = useToasts();
  const outstanding = order.lines.filter((l) => l.quantityOutstanding > 0);
  const [quantities, setQuantities] = useState<Record<string, number>>(() => Object.fromEntries(outstanding.map((l) => [l.productId, l.quantityOutstanding])));
  const [note, setNote] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const lines = outstanding.filter((l) => (quantities[l.productId] ?? 0) > 0).map((l) => ({ productId: l.productId, quantity: quantities[l.productId] }));

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (lines.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      // Same idempotency key for the small-team retry: the first attempt was refused before anything was booked,
      // so the backend has no record for this key yet.
      const result = await withSodOverride(
        (reason) => purchasingApi.receive(order.id, { lines, note: note.trim() || null, sodOverrideReason: reason ?? null }, idem.key),
        askReason,
      );
      if (result) {
        toasts.success(result.replayed ? 'This delivery was already booked' : `Booked ${result.data.receiptNumber}`);
        idem.renew();
        onReceived();
      }
    } catch (e) {
      setError(e); // a plain retry reuses the same key: the delivery is never booked twice
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Receive a delivery">
      <Alert tone="info">Book what actually arrived. Partial deliveries are fine; the rest stays outstanding.</Alert>
      <form onSubmit={onSubmit}>
        {outstanding.map((l) => (
          <Field key={l.productId} label={`${l.productName} (${l.sku}), outstanding ${l.quantityOutstanding}`}>
            <input
              type="number"
              min={0}
              max={l.quantityOutstanding}
              value={quantities[l.productId] ?? 0}
              onChange={(e) => {
                setQuantities({ ...quantities, [l.productId]: Math.max(0, Math.min(l.quantityOutstanding, Number(e.target.value) || 0)) });
                idem.renew();
              }}
            />
          </Field>
        ))}
        <Field label="Note">
          <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <ProblemAlert error={error} title="Delivery not booked" />
        <FormActions>
          <Button type="submit" variant="primary" busy={busy} disabled={lines.length === 0}>
            Book delivery
          </Button>
        </FormActions>
      </form>
    </Card>
  );
}
