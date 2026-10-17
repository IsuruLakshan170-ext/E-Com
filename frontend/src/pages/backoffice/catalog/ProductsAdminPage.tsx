import { type FormEvent, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { catalogApi } from '../../../api/endpoints';
import { errorMessage } from '../../../api/errors';
import type { Product } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { useDialogs } from '../../../components/ui/Dialogs';
import { PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';

export function ProductsAdminPage() {
  useDocumentTitle('Products');
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? '0') || 0;
  const [text, setText] = useState(q);
  const products = useAsync((signal) => catalogApi.searchProducts({ q: q || undefined, includeInactive: true, sort: 'NAME_ASC', page, size: 25 }, signal), [q, page]);
  const { confirm } = useDialogs();
  const toasts = useToasts();

  const onSearch = (event: FormEvent) => {
    event.preventDefault();
    setParams(text.trim() ? { q: text.trim() } : {});
  };

  const onDeactivate = async (p: Product) => {
    const ok = await confirm({
      title: `Hide ${p.name}?`,
      message: 'It disappears from the shop. Past orders keep it. You can activate it again by editing it.',
      confirmLabel: 'Hide product',
      danger: true,
    });
    if (!ok) return;
    try {
      await catalogApi.deactivateProduct(p.id);
      toasts.success('Product hidden');
      products.reload();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  return (
    <div className="page">
      <PageHeader title="Products" actions={<Link className="btn btn-primary" to="/backoffice/catalog/products/new">New product</Link>} />
      <form className="toolbar" onSubmit={onSearch} role="search">
        <input type="search" value={text} placeholder="Name or SKU" aria-label="Search products" onChange={(e) => setText(e.target.value)} />
        <Button type="submit">Search</Button>
      </form>
      <ProblemAlert error={products.error} />
      {products.loading && !products.data && <PageLoading />}
      {products.data && (
        <>
          <DataTable
            rows={products.data.items}
            rowKey={(p) => p.id}
            columns={[
              { key: 'sku', header: 'SKU', cell: (p) => <code>{p.sku}</code> },
              { key: 'name', header: 'Name', cell: (p) => <Link to={`/backoffice/catalog/products/${p.id}`}>{p.name}</Link> },
              { key: 'price', header: 'Price', align: 'right', cell: (p) => <MoneyText value={p.price} /> },
              { key: 'available', header: 'Available', align: 'right', cell: (p) => p.availableQuantity },
              { key: 'state', header: 'State', cell: (p) => <Badge tone={p.active ? 'success' : 'neutral'}>{p.active ? 'Active' : 'Hidden'}</Badge> },
              {
                key: 'actions',
                header: '',
                align: 'right',
                cell: (p) => (
                  <span className="row-actions">
                    <Link to={`/backoffice/inventory?product=${p.id}`}>Stock</Link>
                    {p.active && (
                      <Button size="sm" variant="ghost" onClick={() => onDeactivate(p)}>
                        Hide
                      </Button>
                    )}
                  </span>
                ),
              },
            ]}
          />
          <Pagination page={products.data.page} totalPages={products.data.totalPages} totalItems={products.data.totalItems} onChange={(p) => setParams({ ...(q ? { q } : {}), page: String(p) })} />
        </>
      )}
    </div>
  );
}
