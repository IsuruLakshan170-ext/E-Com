import { Link, useSearchParams } from 'react-router-dom';
import { ordersApi } from '../../api/endpoints';
import { ORDER_STATUSES, type OrderStatus } from '../../api/types';
import { ProblemAlert } from '../../components/ui/Alert';
import { StatusBadge } from '../../components/ui/Badge';
import { DataTable } from '../../components/ui/DataTable';
import { PageHeader } from '../../components/ui/Layout';
import { MoneyText } from '../../components/ui/Money';
import { Pagination } from '../../components/ui/Pagination';
import { PageLoading } from '../../components/ui/Spinner';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';
import { formatDateTime, humanize } from '../../lib/format';

export function MyOrdersPage() {
  useDocumentTitle('Your orders');
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') as OrderStatus | null) ?? undefined;
  const page = Number(params.get('page') ?? '0') || 0;
  const orders = useAsync((signal) => ordersApi.listMine({ status, page, size: 20 }, signal), [status, page]);

  return (
    <div className="page">
      <PageHeader
        title="Your orders"
        actions={
          <select aria-label="Filter by status" value={status ?? ''} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
            <option value="">All orders</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanize(s)}
              </option>
            ))}
          </select>
        }
      />
      <ProblemAlert error={orders.error} />
      {orders.loading && !orders.data && <PageLoading />}
      {orders.data && (
        <>
          <DataTable
            rows={orders.data.items}
            rowKey={(o) => o.id}
            empty="You have not placed any orders yet."
            columns={[
              { key: 'number', header: 'Order', cell: (o) => <Link to={`/account/orders/${o.id}`}>{o.orderNumber}</Link> },
              { key: 'date', header: 'Placed', cell: (o) => formatDateTime(o.createdAt) },
              { key: 'status', header: 'Status', cell: (o) => <StatusBadge status={o.status} /> },
              { key: 'items', header: 'Items', align: 'right', cell: (o) => o.items.reduce((n, l) => n + l.quantity, 0) },
              { key: 'total', header: 'Total', align: 'right', cell: (o) => <MoneyText value={o.total} /> },
            ]}
          />
          <Pagination page={orders.data.page} totalPages={orders.data.totalPages} onChange={(p) => setParams({ ...(status ? { status } : {}), page: String(p) })} />
        </>
      )}
    </div>
  );
}
