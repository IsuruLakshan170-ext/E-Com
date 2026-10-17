import { Link, useSearchParams } from 'react-router-dom';
import { ordersApi } from '../../api/endpoints';
import { errorMessage } from '../../api/errors';
import { Alert, ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { DataTable } from '../../components/ui/DataTable';
import { useDialogs } from '../../components/ui/Dialogs';
import { Card, EmptyState, PageHeader } from '../../components/ui/Layout';
import { MoneyText } from '../../components/ui/Money';
import { Pagination } from '../../components/ui/Pagination';
import { PageLoading } from '../../components/ui/Spinner';
import { useToasts } from '../../components/ui/Toasts';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';
import { formatDateTime } from '../../lib/format';

/**
 * Orders an agent placed above its key's "require my approval above" threshold (backend REVIEW.md §5.3). The
 * order is already placed and its stock reserved; it just can't be paid until you say yes here.
 */
export function PendingApprovalsPage() {
  useDocumentTitle('Approvals');
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? '0') || 0;
  const { confirm } = useDialogs();
  const toasts = useToasts();
  const approvals = useAsync((signal) => ordersApi.listAwaitingApproval({ page, size: 20 }, signal), [page]);

  const onApprove = async (id: string, orderNumber: string) => {
    try {
      await ordersApi.approve(id);
      toasts.success(`${orderNumber} approved; it can now be paid`);
      approvals.reload();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  const onReject = async (id: string, orderNumber: string) => {
    const ok = await confirm({
      title: `Reject ${orderNumber}?`,
      message: 'The order is cancelled and its stock released. This cannot be undone.',
      confirmLabel: 'Reject order',
      danger: true,
    });
    if (!ok) return;
    try {
      await ordersApi.reject(id);
      toasts.success(`${orderNumber} rejected and cancelled`);
      approvals.reload();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  return (
    <div className="page">
      <PageHeader title="Approvals" subtitle="Orders your agent placed that are waiting for your sign-off before they can be paid." />
      <ProblemAlert error={approvals.error} />
      {approvals.loading && !approvals.data && <PageLoading />}
      {approvals.data && approvals.data.items.length === 0 && (
        <EmptyState title="Nothing waiting">Orders your agent places above its key's approval threshold will show up here.</EmptyState>
      )}
      {approvals.data && approvals.data.items.length > 0 && (
        <Card>
          <Alert tone="info">Approving lets the order be paid as usual. Rejecting cancels it and releases the stock.</Alert>
          <DataTable
            rows={approvals.data.items}
            rowKey={(o) => o.id}
            columns={[
              { key: 'number', header: 'Order', cell: (o) => <Link to={`/account/orders/${o.id}`}>{o.orderNumber}</Link> },
              { key: 'placed', header: 'Placed', cell: (o) => formatDateTime(o.createdAt) },
              { key: 'total', header: 'Total', align: 'right', cell: (o) => <MoneyText value={o.total} /> },
              {
                key: 'actions',
                header: '',
                align: 'right',
                cell: (o) => (
                  <>
                    <Button size="sm" variant="primary" onClick={() => onApprove(o.id, o.orderNumber)}>
                      Approve
                    </Button>{' '}
                    <Button size="sm" variant="danger" onClick={() => onReject(o.id, o.orderNumber)}>
                      Reject
                    </Button>
                  </>
                ),
              },
            ]}
          />
          <Pagination page={approvals.data.page} totalPages={approvals.data.totalPages} onChange={(p) => setParams({ page: String(p) })} />
        </Card>
      )}
    </div>
  );
}
