import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { returnsApi } from '../../../api/endpoints';
import { errorMessage } from '../../../api/errors';
import type { ReturnRequest } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { ReturnDetails } from '../../../components/domain/ReturnDetails';
import { withSodOverride } from '../../../components/domain/sod';
import { useSodPrompt } from '../../../components/domain/useSodPrompt';
import { ProblemAlert } from '../../../components/ui/Alert';
import { StatusBadge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { useDialogs } from '../../../components/ui/Dialogs';
import { PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { SCOPES } from '../../../lib/scopes';

export function AdminReturnPage() {
  const { id = '' } = useParams();
  const rma = useAsync((signal) => returnsApi.adminGet(id, signal), [id]);
  const { hasScope } = useAuth();
  const { prompt, confirm } = useDialogs();
  const askReason = useSodPrompt();
  const toasts = useToasts();
  const [busy, setBusy] = useState(false);
  useDocumentTitle(rma.data ? `Return ${rma.data.rmaNumber}` : 'Return');

  if (rma.loading && !rma.data) return <PageLoading />;
  if (!rma.data) return <ProblemAlert error={rma.error} title="Return not found" />;
  const r = rma.data;
  const next = (status: string) => r.allowedNextStatuses.includes(status as ReturnRequest['status']);

  const act = async (work: () => Promise<ReturnRequest | null>, success: string) => {
    setBusy(true);
    try {
      const updated = await work();
      if (updated) {
        rma.setData(updated);
        toasts.success(success);
      }
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const onDecide = async (approve: boolean) => {
    const note = await prompt({
      title: approve ? 'Approve return' : 'Reject return',
      message: approve ? 'The customer is told to send the items back.' : 'The customer sees this note.',
      label: 'Note (optional)',
      maxLength: 500,
      required: !approve,
      multiline: true,
      confirmLabel: approve ? 'Approve' : 'Reject',
    });
    if (note === null) return;
    await act(() => (approve ? returnsApi.approve(r.id, { note: note || null }) : returnsApi.reject(r.id, { note })), approve ? 'Return approved' : 'Return rejected');
  };

  const onReceive = async () => {
    if (!(await confirm({ title: 'Book the returned goods?', message: 'The items go back into stock at their original cost.', confirmLabel: 'Goods received' }))) return;
    await act(() => withSodOverride((reason) => returnsApi.receive(r.id, reason), askReason), 'Goods received');
  };

  const onRefund = async () => {
    if (!(await confirm({ title: 'Refund the customer?', message: 'A credit note is issued and the money is refunded, including the proportional tax.', confirmLabel: 'Refund' }))) return;
    await act(() => withSodOverride((reason) => returnsApi.refund(r.id, reason), askReason), 'Refund issued');
  };

  return (
    <div className="page">
      <PageHeader
        title={`Return ${r.rmaNumber}`}
        subtitle={<StatusBadge status={r.status} />}
        actions={
          <>
            {hasScope(SCOPES.ORDERS_ADMIN) && <Link to={`/backoffice/sales/orders/${r.orderId}`}>View order</Link>}
            {next('APPROVED') && hasScope(SCOPES.RETURNS_MANAGE) && (
              <>
                <Button variant="primary" busy={busy} onClick={() => onDecide(true)}>
                  Approve
                </Button>
                <Button variant="danger" busy={busy} onClick={() => onDecide(false)}>
                  Reject
                </Button>
              </>
            )}
            {next('RECEIVED') && hasScope(SCOPES.INVENTORY_WRITE) && (
              <Button variant="primary" busy={busy} onClick={onReceive}>
                Goods received
              </Button>
            )}
            {next('REFUNDED') && hasScope(SCOPES.FINANCE_WRITE) && (
              <Button variant="primary" busy={busy} onClick={onRefund}>
                Refund
              </Button>
            )}
          </>
        }
      />
      <ReturnDetails rma={r} />
    </div>
  );
}
