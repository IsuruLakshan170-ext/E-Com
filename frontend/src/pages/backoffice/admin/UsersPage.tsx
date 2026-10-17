import { Link, useSearchParams } from 'react-router-dom';
import { adminApi } from '../../../api/endpoints';
import { ROLES, type Role } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { DataTable } from '../../../components/ui/DataTable';
import { PageHeader } from '../../../components/ui/Layout';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDate, humanize } from '../../../lib/format';

export function UsersPage() {
  useDocumentTitle('Users');
  const [params, setParams] = useSearchParams();
  const role = (params.get('role') as Role | null) ?? undefined;
  const page = Number(params.get('page') ?? '0') || 0;
  const users = useAsync((signal) => adminApi.users({ role, page, size: 50 }, signal), [role, page]);

  return (
    <div className="page">
      <PageHeader
        title="Users"
        subtitle="Staff are created by registering normally and then given a role here."
        actions={
          <select aria-label="Role" value={role ?? ''} onChange={(e) => setParams(e.target.value ? { role: e.target.value } : {})}>
            <option value="">All roles</option>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {humanize(r)}
              </option>
            ))}
          </select>
        }
      />
      <ProblemAlert error={users.error} />
      {users.loading && !users.data && <PageLoading />}
      {users.data && (
        <>
          <DataTable
            rows={users.data.items}
            rowKey={(u) => u.id}
            empty="No users."
            columns={[
              { key: 'email', header: 'Email', cell: (u) => <Link to={`/backoffice/admin/users/${u.id}`}>{u.email}</Link> },
              { key: 'name', header: 'Name', cell: (u) => u.fullName },
              { key: 'role', header: 'Role', cell: (u) => humanize(u.role) },
              { key: 'state', header: 'State', cell: (u) => <Badge tone={u.enabled ? 'success' : 'neutral'}>{u.enabled ? 'Enabled' : 'Disabled'}</Badge> },
              { key: 'since', header: 'Since', cell: (u) => formatDate(u.createdAt) },
            ]}
          />
          <Pagination page={users.data.page} totalPages={users.data.totalPages} totalItems={users.data.totalItems} onChange={(p) => setParams({ ...(role ? { role } : {}), page: String(p) })} />
        </>
      )}
    </div>
  );
}
