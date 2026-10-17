import { Link, useSearchParams } from 'react-router-dom';
import { returnsApi } from '../../../api/endpoints';
import { RETURN_STATUSES, type ReturnStatus } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { StatusBadge } from '../../../components/ui/Badge';
import { DataTable } from '../../../components/ui/DataTable';
import { PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize } from '../../../lib/format';

export function AdminReturnsPage() {
  useDocumentTitle('Returns');
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') as ReturnStatus | null) ?? undefined;
  const page = Number(params.get('page') ?? '0') || 0;
  const returns = useAsync((signal) => returnsApi.adminList({ status, page, size: 25 }, signal), [status, page]);

  return (
    <div className="page">
      <PageHeader
        title="Returns"
        subtitle="Approve, receive and refund are three separate steps, normally done by three different people."
        actions={
          <select aria-label="Status" value={status ?? ''} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
            <option value="">All statuses</option>
            {RETURN_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanize(s)}
              </option>
            ))}
          </select>
        }
      />
      <ProblemAlert error={returns.error} />
      {returns.loading && !returns.data && <PageLoading />}
      {returns.data && (
        <>
          <DataTable
            rows={returns.data.items}
            rowKey={(r) => r.id}
            empty="No returns."
            columns={[
              { key: 'rma', header: 'Return', cell: (r) => <Link to={`/backoffice/sales/returns/${r.id}`}>{r.rmaNumber}</Link> },
              { key: 'requested', header: 'Requested', cell: (r) => formatDateTime(r.createdAt) },
              { key: 'status', header: 'Status', cell: (r) => <StatusBadge status={r.status} /> },
              { key: 'reason', header: 'Reason', cell: (r) => r.reason },
              { key: 'value', header: 'Value', align: 'right', cell: (r) => <MoneyText value={r.merchandiseTotal} /> },
            ]}
          />
          <Pagination page={returns.data.page} totalPages={returns.data.totalPages} onChange={(p) => setParams({ ...(status ? { status } : {}), page: String(p) })} />
        </>
      )}
    </div>
  );
}
