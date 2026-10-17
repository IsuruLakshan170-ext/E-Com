import { type FormEvent, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { inventoryApi } from '../../../api/endpoints';
import { errorMessage } from '../../../api/errors';
import type { InventoryItem } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { ProductPicker } from '../../../components/domain/ProductPicker';
import { SupplierPicker } from '../../../components/domain/SupplierPicker';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, EmptyState, KeyValueList, PageHeader, Stat } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle, useIdempotencyKey } from '../../../hooks/misc';
import { SCOPES } from '../../../lib/scopes';

export function StockPage() {
  useDocumentTitle('Stock');
  const [params, setParams] = useSearchParams();
  const productId = params.get('product') ?? '';
  const { hasScope } = useAuth();
  const item = useAsync<InventoryItem | null>((signal) => (productId ? inventoryApi.getItem(productId, signal) : Promise.resolve(null)), [productId]);

  return (
    <div className="page">
      <PageHeader title="Stock" subtitle="On hand, reserved for orders, and available to sell." />
      <Card title="Find a product">
        <ProductPicker includeInactive={hasScope(SCOPES.CATALOG_WRITE)} onSelect={(p) => setParams({ product: p.id })} />
      </Card>
      {!productId && <EmptyState title="Choose a product to see and manage its stock" />}
      {productId && item.loading && !item.data && <PageLoading />}
      <ProblemAlert error={item.error} />
      {item.data && <StockDetails item={item.data} onChange={item.setData} canWrite={hasScope(SCOPES.INVENTORY_WRITE)} />}
    </div>
  );
}

function StockDetails({ item, onChange, canWrite }: { item: InventoryItem; onChange: (item: InventoryItem) => void; canWrite: boolean }) {
  return (
    <>
      <Card title={`${item.name} (${item.sku})`} actions={<Link to={`/backoffice/inventory/movements?product=${item.productId}`}>Stock ledger</Link>}>
        <div className="stats">
          <Stat label="On hand" value={item.onHand} />
          <Stat label="Reserved for orders" value={item.reserved} />
          <Stat label="Available to sell" value={item.available} />
          <Stat label="Average unit cost" value={<MoneyText value={item.unitCost} />} />
          <Stat label="Stock value" value={<MoneyText value={item.stockValue} />} />
        </div>
        {!item.active && <p className="muted">This product is hidden from the shop.</p>}
      </Card>
      {canWrite && (
        <div className="grid-2">
          <AdjustForm item={item} onDone={onChange} />
          <ReorderPolicyForm item={item} onDone={onChange} />
        </div>
      )}
      {!canWrite && (
        <Card title="Reorder policy">
          <KeyValueList
            items={[
              { label: 'Reorder point', value: item.reorderPoint },
              { label: 'Reorder quantity', value: item.reorderQuantity },
            ]}
          />
        </Card>
      )}
    </>
  );
}

function AdjustForm({ item, onDone }: { item: InventoryItem; onDone: (item: InventoryItem) => void }) {
  const toasts = useToasts();
  const idem = useIdempotencyKey();
  const [delta, setDelta] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const value = Number(delta);
  const valid = Number.isInteger(value) && value !== 0 && reason.trim().length > 0;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid) return;
    setBusy(true);
    setError(null);
    try {
      const result = await inventoryApi.adjust(item.productId, { delta: value, reason: reason.trim() }, idem.key);
      onDone(result.data);
      toasts.success(result.replayed ? 'Already applied earlier (nothing changed twice)' : 'Stock adjusted');
      setDelta('');
      setReason('');
      idem.renew();
    } catch (e) {
      setError(e); // retrying reuses the same key, so the adjustment can never be applied twice (R7)
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Adjust stock">
      <form onSubmit={onSubmit}>
        <p className="muted small">Count corrections, damage or loss. Units reserved for orders cannot be removed.</p>
        <FormRow>
          <Field label="Change" hint="e.g. -2 or 5">
            <input
              type="number"
              step={1}
              value={delta}
              onChange={(e) => {
                setDelta(e.target.value);
                idem.renew();
              }}
            />
          </Field>
        </FormRow>
        <Field label="Reason" required>
          <input
            value={reason}
            maxLength={200}
            onChange={(e) => {
              setReason(e.target.value);
              idem.renew();
            }}
          />
        </Field>
        <ProblemAlert error={error} title="Not adjusted" />
        <FormActions>
          <Button type="submit" variant="primary" busy={busy} disabled={!valid}>
            Apply adjustment
          </Button>
        </FormActions>
      </form>
    </Card>
  );
}

function ReorderPolicyForm({ item, onDone }: { item: InventoryItem; onDone: (item: InventoryItem) => void }) {
  const toasts = useToasts();
  const [point, setPoint] = useState(String(item.reorderPoint));
  const [quantity, setQuantity] = useState(String(item.reorderQuantity));
  const [supplierId, setSupplierId] = useState(item.preferredSupplierId ?? '');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setPoint(String(item.reorderPoint));
    setQuantity(String(item.reorderQuantity));
    setSupplierId(item.preferredSupplierId ?? '');
  }, [item]);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      onDone(await inventoryApi.setReorderPolicy(item.productId, { reorderPoint: Number(point) || 0, reorderQuantity: Number(quantity) || 0, preferredSupplierId: supplierId || null }));
      toasts.success('Reorder policy saved');
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Reorder policy">
      <form onSubmit={onSubmit}>
        <FormRow>
          <Field label="Reorder point" hint="Suggest buying when available drops to this">
            <input type="number" min={0} value={point} onChange={(e) => setPoint(e.target.value)} />
          </Field>
          <Field label="Reorder quantity">
            <input type="number" min={0} value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          </Field>
        </FormRow>
        <Field label="Preferred supplier" group>
          <SupplierPicker value={supplierId} onChange={setSupplierId} allowNone />
        </Field>
        <FormActions>
          <Button type="submit" busy={busy}>
            Save policy
          </Button>
        </FormActions>
      </form>
    </Card>
  );
}
