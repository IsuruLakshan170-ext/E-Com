import { type FormEvent, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { purchasingApi } from '../../../api/endpoints';
import { useAuth } from '../../../auth/AuthContext';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { PageHeader } from '../../../components/ui/Layout';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { SCOPES } from '../../../lib/scopes';

export function SuppliersPage() {
  useDocumentTitle('Suppliers');
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const includeInactive = params.get('inactive') === '1';
  const page = Number(params.get('page') ?? '0') || 0;
  const [text, setText] = useState(q);
  const { hasScope } = useAuth();
  const suppliers = useAsync((signal) => purchasingApi.searchSuppliers({ q: q || undefined, includeInactive: includeInactive || undefined, page, size: 25 }, signal), [q, includeInactive, page]);

  const onSearch = (event: FormEvent) => {
    event.preventDefault();
    setParams({ ...(text.trim() ? { q: text.trim() } : {}), ...(includeInactive ? { inactive: '1' } : {}) });
  };

  return (
    <div className="page">
      <PageHeader
        title="Suppliers"
        actions={hasScope(SCOPES.PURCHASING_WRITE) && <Link className="btn btn-primary" to="/backoffice/purchasing/suppliers/new">New supplier</Link>}
      />
      <form className="toolbar" onSubmit={onSearch} role="search">
        <input type="search" value={text} placeholder="Name or code" aria-label="Search suppliers" onChange={(e) => setText(e.target.value)} />
        <label className="checkbox">
          <input
            type="checkbox"
            checked={includeInactive}
            onChange={(e) => setParams({ ...(q ? { q } : {}), ...(e.target.checked ? { inactive: '1' } : {}) })}
          />
          Show inactive
        </label>
        <Button type="submit">Search</Button>
      </form>
      <ProblemAlert error={suppliers.error} />
      {suppliers.loading && !suppliers.data && <PageLoading />}
      {suppliers.data && (
        <>
          <DataTable
            rows={suppliers.data.items}
            rowKey={(s) => s.id}
            empty="No suppliers."
            columns={[
              { key: 'code', header: 'Code', cell: (s) => <code>{s.code}</code> },
              { key: 'name', header: 'Name', cell: (s) => <Link to={`/backoffice/purchasing/suppliers/${s.id}`}>{s.name}</Link> },
              { key: 'contact', header: 'Contact', cell: (s) => s.email ?? s.phone ?? '—' },
              { key: 'terms', header: 'Payment terms', align: 'right', cell: (s) => `${s.paymentTermsDays} days` },
              { key: 'state', header: 'State', cell: (s) => <Badge tone={s.active ? 'success' : 'neutral'}>{s.active ? 'Active' : 'Inactive'}</Badge> },
            ]}
          />
          <Pagination page={suppliers.data.page} totalPages={suppliers.data.totalPages} onChange={(p) => setParams({ ...(q ? { q } : {}), page: String(p) })} />
        </>
      )}
    </div>
  );
}
