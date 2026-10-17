import { type FormEvent, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ordersApi } from '../../../api/endpoints';
import { ORDER_STATUSES, type OrderStatus } from '../../../api/types';
import { Alert, ProblemAlert } from '../../../components/ui/Alert';
import { StatusBadge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize } from '../../../lib/format';
import { ORDER_NUMBER_PATTERN } from '../../../lib/validation';

export function AdminOrdersPage() {
  useDocumentTitle('Orders');
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') as OrderStatus | null) ?? undefined;
  const orderNumber = (params.get('orderNumber') ?? '').trim();
  const page = Number(params.get('page') ?? '0') || 0;
  const [search, setSearch] = useState(orderNumber);
  const searchValid = ORDER_NUMBER_PATTERN.test(orderNumber);
  const orders = useAsync(
    (signal) => ordersApi.adminList({ status, orderNumber: searchValid ? orderNumber : undefined, page, size: 25 }, signal),
    [status, orderNumber, page],
  );

  /** Changing a filter starts again at page 1. */
  const set = (key: string, value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  };

  const onSearch = (event: FormEvent) => {
    event.preventDefault();
    set('orderNumber', search.trim() || undefined);
  };

  return (
    <div className="page">
      <PageHeader
        title="Orders"
        actions={
          <select aria-label="Status" value={status ?? ''} onChange={(e) => set('status', e.target.value || undefined)}>
            <option value="">All statuses</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanize(s)}
              </option>
            ))}
          </select>
        }
      />
      <form className="toolbar" role="search" onSubmit={onSearch}>
        <input
          type="search"
          value={search}
          placeholder="Order number, e.g. ORD-20261013-ABCD2345"
          aria-label="Order number"
          spellCheck={false}
          onChange={(e) => setSearch(e.target.value)}
        />
        <Button type="submit">Find order</Button>
        {orderNumber && (
          <Button
            variant="ghost"
            onClick={() => {
              setSearch('');
              set('orderNumber', undefined);
            }}
          >
            Clear
          </Button>
        )}
      </form>
      {orderNumber && !searchValid && <Alert tone="warning">Order numbers contain only letters, digits and dashes, e.g. ORD-20261013-ABCD2345.</Alert>}
      <ProblemAlert error={orders.error} />
      {orders.loading && !orders.data && <PageLoading />}
      {orders.data && (
        <>
          <DataTable
            rows={orders.data.items}
            rowKey={(o) => o.id}
            empty={searchValid ? `No order ${orderNumber.toUpperCase()}${status ? ` with status ${humanize(status)}` : ''}.` : 'No orders.'}
            columns={[
              { key: 'number', header: 'Order', cell: (o) => <Link to={`/backoffice/sales/orders/${o.id}`}>{o.orderNumber}</Link> },
              { key: 'placed', header: 'Placed', cell: (o) => formatDateTime(o.createdAt) },
              { key: 'customer', header: 'Ship to', cell: (o) => `${o.shippingAddress.recipientName}, ${o.shippingAddress.city} (${o.shippingAddress.countryCode})` },
              { key: 'status', header: 'Status', cell: (o) => <StatusBadge status={o.status} /> },
              { key: 'total', header: 'Total', align: 'right', cell: (o) => <MoneyText value={o.total} /> },
            ]}
          />
          <Pagination
            page={orders.data.page}
            totalPages={orders.data.totalPages}
            totalItems={orders.data.totalItems}
            onChange={(p) => set('page', String(p))}
          />
        </>
      )}
    </div>
  );
}
