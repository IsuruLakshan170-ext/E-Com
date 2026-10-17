import { useSearchParams } from 'react-router-dom';
import { inventoryApi } from '../../../api/endpoints';
import { MOVEMENT_TYPES, type MovementType } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize, shortId } from '../../../lib/format';

export function MovementsPage() {
  useDocumentTitle('Stock ledger');
  const [params, setParams] = useSearchParams();
  const productId = params.get('product') ?? undefined;
  const type = (params.get('type') as MovementType | null) ?? undefined;
  const page = Number(params.get('page') ?? '0') || 0;
  const movements = useAsync((signal) => inventoryApi.movements({ productId, type, page, size: 50 }, signal), [productId, type, page]);

  const set = (key: string, value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  };

  return (
    <div className="page">
      <PageHeader title="Stock ledger" subtitle="Every change to on-hand stock, with its value. Rows can never be edited." />
      <div className="toolbar">
        <select aria-label="Movement type" value={type ?? ''} onChange={(e) => set('type', e.target.value || undefined)}>
          <option value="">All movement types</option>
          {MOVEMENT_TYPES.map((t) => (
            <option key={t} value={t}>
              {humanize(t)}
            </option>
          ))}
        </select>
        {productId && (
          <Button variant="ghost" onClick={() => set('product', undefined)}>
            Show all products
          </Button>
        )}
      </div>
      <ProblemAlert error={movements.error} />
      {movements.loading && !movements.data && <PageLoading />}
      {movements.data && (
        <>
          <DataTable
            rows={movements.data.items}
            rowKey={(m) => m.id}
            empty="No movements."
            columns={[
              { key: 'when', header: 'When', cell: (m) => formatDateTime(m.createdAt) },
              {
                key: 'product',
                header: 'Product',
                cell: (m) => (
                  <button type="button" className="link-button" title="Show only this product" onClick={() => set('product', m.productId)}>
                    {m.productName} <span className="muted">({m.sku})</span>
                  </button>
                ),
              },
              { key: 'type', header: 'Type', cell: (m) => <Badge tone={m.quantity > 0 ? 'success' : 'warning'}>{humanize(m.type)}</Badge> },
              { key: 'qty', header: 'Qty', align: 'right', cell: (m) => (m.quantity > 0 ? `+${m.quantity}` : m.quantity) },
              { key: 'after', header: 'On hand after', align: 'right', cell: (m) => m.stockAfter },
              { key: 'value', header: 'Value', align: 'right', cell: (m) => (m.value ? <MoneyText value={m.value} /> : '—') },
              { key: 'note', header: 'Note / reference', cell: (m) => m.note ?? (m.referenceType ? `${m.referenceType} ${shortId(m.referenceId)}` : '—') },
            ]}
          />
          <Pagination page={movements.data.page} totalPages={movements.data.totalPages} onChange={(p) => set('page', String(p))} />
        </>
      )}
    </div>
  );
}
