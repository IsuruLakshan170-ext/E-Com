import { Link, useSearchParams } from 'react-router-dom';
import { inventoryApi } from '../../../api/endpoints';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Card, PageHeader, Stat } from '../../../components/ui/Layout';
import { DataTable } from '../../../components/ui/DataTable';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime } from '../../../lib/format';

export function ValuationPage() {
  useDocumentTitle('Inventory valuation');
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? '0') || 0;
  const valuation = useAsync((signal) => inventoryApi.valuation({ page, size: 100 }, signal), [page]);
  return (
    <div className="page">
      <PageHeader title="Inventory valuation" subtitle="Exact value of stock on hand; it always equals the Inventory account in the ledger." />
      <ProblemAlert error={valuation.error} />
      {valuation.loading && !valuation.data && <PageLoading />}
      {valuation.data && (
        <>
          <Card>
            <div className="stats">
              <Stat label="Units on hand" value={valuation.data.totalUnits} />
              <Stat label="Total value" value={<MoneyText value={valuation.data.totalValue} />} hint={`as of ${formatDateTime(valuation.data.asOf)}`} />
            </div>
          </Card>
          <DataTable
            rows={valuation.data.items.items}
            rowKey={(i) => i.productId}
            empty="No stock on hand."
            columns={[
              { key: 'product', header: 'Product', cell: (i) => <Link to={`/backoffice/inventory?product=${i.productId}`}>{i.name} ({i.sku})</Link> },
              { key: 'onHand', header: 'On hand', align: 'right', cell: (i) => i.onHand },
              { key: 'reserved', header: 'Reserved', align: 'right', cell: (i) => i.reserved },
              { key: 'cost', header: 'Avg. unit cost', align: 'right', cell: (i) => <MoneyText value={i.unitCost} /> },
              { key: 'value', header: 'Value', align: 'right', cell: (i) => <MoneyText value={i.stockValue} /> },
            ]}
          />
          <Pagination page={valuation.data.items.page} totalPages={valuation.data.items.totalPages} onChange={(p) => setParams({ page: String(p) })} />
        </>
      )}
    </div>
  );
}
