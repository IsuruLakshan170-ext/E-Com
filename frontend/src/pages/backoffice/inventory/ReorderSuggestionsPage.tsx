import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { inventoryApi, purchasingApi } from '../../../api/endpoints';
import type { DraftsFromSuggestions } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { Alert, ProblemAlert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { useDialogs } from '../../../components/ui/Dialogs';
import { PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { SCOPES } from '../../../lib/scopes';

export function ReorderSuggestionsPage() {
  useDocumentTitle('Reorder suggestions');
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? '0') || 0;
  const { hasScope } = useAuth();
  const { confirm } = useDialogs();
  const suggestions = useAsync((signal) => inventoryApi.reorderSuggestions({ page, size: 50 }, signal), [page]);
  const canPurchase = hasScope(SCOPES.PURCHASING_WRITE);
  const [result, setResult] = useState<DraftsFromSuggestions | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const onCreateDrafts = async () => {
    const ok = await confirm({
      title: 'Create draft purchase orders?',
      message: 'One draft per preferred supplier, for every product below its reorder point. Drafts still need to be submitted and approved.',
      confirmLabel: 'Create drafts',
    });
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await purchasingApi.draftsFromSuggestions());
      suggestions.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader
        title="Reorder suggestions"
        subtitle="Available stock plus open purchase orders is at or below the reorder point."
        actions={canPurchase && <Button variant="primary" busy={busy} onClick={onCreateDrafts}>Create draft purchase orders</Button>}
      />
      <ProblemAlert error={error} />
      {result && (
        <Alert tone="success" title={`${result.created.length} draft purchase order(s) created`}>
          <ul className="alert-list">
            {result.created.map((po) => (
              <li key={po.id}>
                <Link to={`/backoffice/purchasing/orders/${po.id}`}>{po.poNumber}</Link>
              </li>
            ))}
          </ul>
          {result.skippedProductIdsWithoutSupplier.length > 0 && <p>{result.skippedProductIdsWithoutSupplier.length} product(s) skipped: no preferred supplier.</p>}
          {result.skippedInactiveSupplierIds.length > 0 && <p>{result.skippedInactiveSupplierIds.length} supplier(s) skipped: inactive.</p>}
        </Alert>
      )}
      <ProblemAlert error={suggestions.error} />
      {suggestions.loading && !suggestions.data && <PageLoading />}
      {suggestions.data && (
        <>
          <DataTable
            rows={suggestions.data.items}
            rowKey={(s) => s.productId}
            empty="Nothing to reorder."
            columns={[
              { key: 'product', header: 'Product', cell: (s) => <Link to={`/backoffice/inventory?product=${s.productId}`}>{s.name} ({s.sku})</Link> },
              { key: 'available', header: 'Available', align: 'right', cell: (s) => s.available },
              { key: 'onOrder', header: 'On order', align: 'right', cell: (s) => s.onOrder },
              { key: 'point', header: 'Reorder point', align: 'right', cell: (s) => s.reorderPoint },
              { key: 'suggested', header: 'Suggested', align: 'right', cell: (s) => <strong>{s.suggestedQuantity}</strong> },
              { key: 'cost', header: 'Est. unit cost', align: 'right', cell: (s) => <MoneyText value={s.estimatedUnitCost} /> },
              { key: 'supplier', header: 'Preferred supplier', cell: (s) => s.preferredSupplierName ?? 'None' },
            ]}
          />
          <Pagination page={suggestions.data.page} totalPages={suggestions.data.totalPages} onChange={(p) => setParams({ page: String(p) })} />
        </>
      )}
    </div>
  );
}
