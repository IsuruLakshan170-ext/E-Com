import { useParams, useSearchParams } from 'react-router-dom';
import { accountApi } from '../../api/endpoints';
import { ProblemAlert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { DataTable } from '../../components/ui/DataTable';
import { Card, PageHeader } from '../../components/ui/Layout';
import { Pagination } from '../../components/ui/Pagination';
import { PageLoading } from '../../components/ui/Spinner';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';
import { formatDateTime, humanize, shortId } from '../../lib/format';

/**
 * "What did my agent do?" (backend REVIEW.md §5.3): every audited action one of YOUR OWN keys took,
 * newest first. The backend checks ownership, so this never leaks another person's key's activity.
 */
export function ApiKeyActivityPage() {
  useDocumentTitle('Agent activity');
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? '0') || 0;
  const activity = useAsync((signal) => accountApi.apiKeyActivity(id, { page, size: 50 }, signal), [id, page]);

  return (
    <div className="page">
      <PageHeader title="Agent activity" subtitle="Every action this key took, in order." />
      <Card>
        <ProblemAlert error={activity.error} />
        {activity.loading && !activity.data && <PageLoading />}
        {activity.data && (
          <>
            <DataTable
              rows={activity.data.items}
              rowKey={(e) => e.id}
              empty="This key hasn't done anything yet."
              columns={[
                { key: 'when', header: 'When', cell: (e) => formatDateTime(e.occurredAt) },
                { key: 'action', header: 'Action', cell: (e) => <Badge>{humanize(e.action)}</Badge> },
                { key: 'target', header: 'On', cell: (e) => (e.targetType ? `${e.targetType} ${shortId(e.targetId)}` : '—') },
                { key: 'details', header: 'Details', cell: (e) => e.details ?? '—' },
              ]}
            />
            <Pagination page={activity.data.page} totalPages={activity.data.totalPages} onChange={(p) => setParams({ page: String(p) })} />
          </>
        )}
      </Card>
    </div>
  );
}
