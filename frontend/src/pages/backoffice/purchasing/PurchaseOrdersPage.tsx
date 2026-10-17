import { Link, useSearchParams } from 'react-router-dom';
import { purchasingApi } from '../../../api/endpoints';
import { PURCHASE_ORDER_STATUSES, type PurchaseOrderStatus } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { ProblemAlert } from '../../../components/ui/Alert';
import { StatusBadge } from '../../../components/ui/Badge';
import { DataTable } from '../../../components/ui/DataTable';
import { PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDate, humanize } from '../../../lib/format';
import { SCOPES } from '../../../lib/scopes';

export function PurchaseOrdersPage() {
  useDocumentTitle('Purchase orders');
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') as PurchaseOrderStatus | null) ?? undefined;
  const page = Number(params.get('page') ?? '0') || 0;
  const { hasScope } = useAuth();
  const orders = useAsync((signal) => purchasingApi.listOrders({ status, page, size: 25 }, signal), [status, page]);

  return (
    <div className="page">
      <PageHeader
        title="Purchase orders"
        actions={
          <>
            <select aria-label="Status" value={status ?? ''} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
              <option value="">All statuses</option>
              {PURCHASE_ORDER_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {humanize(s)}
                </option>
              ))}
            </select>
            {hasScope(SCOPES.PURCHASING_WRITE) && (
              <Link className="btn btn-primary" to="/backoffice/purchasing/orders/new">
                New purchase order
              </Link>
            )}
          </>
        }
      />
      <ProblemAlert error={orders.error} />
      {orders.loading && !orders.data && <PageLoading />}
      {orders.data && (
        <>
          <DataTable
            rows={orders.data.items}
            rowKey={(o) => o.id}
            empty="No purchase orders."
            columns={[
              { key: 'number', header: 'PO', cell: (o) => <Link to={`/backoffice/purchasing/orders/${o.id}`}>{o.poNumber}</Link> },
              { key: 'supplier', header: 'Supplier', cell: (o) => o.supplierName },
              { key: 'status', header: 'Status', cell: (o) => <StatusBadge status={o.status} /> },
              { key: 'expected', header: 'Expected', cell: (o) => formatDate(o.expectedDate) },
              { key: 'total', header: 'Total', align: 'right', cell: (o) => <MoneyText value={o.total} /> },
            ]}
          />
          <Pagination page={orders.data.page} totalPages={orders.data.totalPages} onChange={(p) => setParams({ ...(status ? { status } : {}), page: String(p) })} />
        </>
      )}
    </div>
  );
}
