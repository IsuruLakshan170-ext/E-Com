import { Link, useSearchParams } from 'react-router-dom';
import { returnsApi } from '../../api/endpoints';
import { ProblemAlert } from '../../components/ui/Alert';
import { StatusBadge } from '../../components/ui/Badge';
import { DataTable } from '../../components/ui/DataTable';
import { PageHeader } from '../../components/ui/Layout';
import { MoneyText } from '../../components/ui/Money';
import { Pagination } from '../../components/ui/Pagination';
import { PageLoading } from '../../components/ui/Spinner';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';
import { formatDateTime } from '../../lib/format';

export function MyReturnsPage() {
  useDocumentTitle('Your returns');
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? '0') || 0;
  const returns = useAsync((signal) => returnsApi.listMine({ page, size: 20 }, signal), [page]);
  return (
    <div className="page">
      <PageHeader title="Your returns" subtitle="Returns can be requested from a delivered order." />
      <ProblemAlert error={returns.error} />
      {returns.loading && !returns.data && <PageLoading />}
      {returns.data && (
        <>
          <DataTable
            rows={returns.data.items}
            rowKey={(r) => r.id}
            empty="No returns."
            columns={[
              { key: 'rma', header: 'Return', cell: (r) => <Link to={`/account/returns/${r.id}`}>{r.rmaNumber}</Link> },
              { key: 'date', header: 'Requested', cell: (r) => formatDateTime(r.createdAt) },
              { key: 'status', header: 'Status', cell: (r) => <StatusBadge status={r.status} /> },
              { key: 'value', header: 'Value', align: 'right', cell: (r) => <MoneyText value={r.merchandiseTotal} /> },
            ]}
          />
          <Pagination page={returns.data.page} totalPages={returns.data.totalPages} onChange={(p) => setParams({ page: String(p) })} />
        </>
      )}
    </div>
  );
}
