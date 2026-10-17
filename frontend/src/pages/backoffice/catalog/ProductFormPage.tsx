import { type FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { catalogApi } from '../../../api/endpoints';
import { fieldErrors } from '../../../api/errors';
import type { Product } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { STORE_CURRENCY, minorToInput, parseAmountToMinor } from '../../../lib/format';
import { IMAGE_PATH_PATTERN, SKU_PATTERN, blankToNull } from '../../../lib/validation';

interface Draft {
  sku: string;
  name: string;
  description: string;
  imageUrl: string;
  price: string;
  categoryId: string;
  active: boolean;
  initialStock: string;
  unitCost: string;
}

const EMPTY: Draft = { sku: '', name: '', description: '', imageUrl: '', price: '', categoryId: '', active: true, initialStock: '0', unitCost: '' };

export function ProductFormPage() {
  const { id } = useParams();
  const editing = Boolean(id);
  useDocumentTitle(editing ? 'Edit product' : 'New product');
  const navigate = useNavigate();
  const toasts = useToasts();
  const categories = useAsync((signal) => catalogApi.listCategories(signal), []);
  const existing = useAsync<Product | null>((signal) => (id ? catalogApi.getProduct(id, true, signal) : Promise.resolve(null)), [id]);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const p = existing.data;
    if (p) {
      setDraft({
        sku: p.sku,
        name: p.name,
        description: p.description ?? '',
        imageUrl: p.imageUrl ?? '',
        price: minorToInput(p.price.amountMinor, p.price.currency),
        categoryId: p.categoryId ?? '',
        active: p.active,
        initialStock: '0',
        unitCost: '',
      });
    }
  }, [existing.data]);

  if (editing && existing.loading && !existing.data) return <PageLoading />;
  if (editing && !existing.data) return <ProblemAlert error={existing.error} title="Product not found" />;

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((d) => ({ ...d, [key]: value }));
  const priceMinor = parseAmountToMinor(draft.price);
  const initialStock = Number(draft.initialStock);
  const unitCostMinor = draft.unitCost.trim() ? parseAmountToMinor(draft.unitCost) : null;
  const server = fieldErrors(error);
  const problems = {
    sku: SKU_PATTERN.test(draft.sku) ? null : '3-64 characters: A-Z, 0-9, - or _',
    name: draft.name.trim() ? null : 'Required',
    imageUrl: !draft.imageUrl.trim() || IMAGE_PATH_PATTERN.test(draft.imageUrl.trim()) ? null : 'A path on this site, e.g. /products/yoga-mat.jpg',
    price: priceMinor === null ? `Amount in ${STORE_CURRENCY}, e.g. 19.99` : null,
    initialStock: !editing && (!Number.isInteger(initialStock) || initialStock < 0 || initialStock > 1_000_000) ? 'Whole number from 0' : null,
    unitCost:
      !editing && initialStock > 0 && unitCostMinor === null ? 'Needed to value the opening stock' : draft.unitCost.trim() && unitCostMinor === null ? 'Invalid amount' : null,
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (Object.values(problems).some(Boolean) || priceMinor === null) return;
    setBusy(true);
    setError(null);
    try {
      const common = {
        sku: draft.sku,
        name: draft.name.trim(),
        description: blankToNull(draft.description),
        imageUrl: blankToNull(draft.imageUrl.trim()),
        priceMinor,
        categoryId: draft.categoryId || null,
        active: draft.active,
      };
      const saved = id
        ? await catalogApi.updateProduct(id, common)
        : await catalogApi.createProduct({ ...common, initialStock, unitCostMinor: unitCostMinor ?? undefined });
      toasts.success(id ? 'Product saved' : 'Product created');
      navigate(`/backoffice/catalog/products/${saved.id}`, { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const show = (key: keyof typeof problems) => (touched ? problems[key] : null) ?? server[key] ?? null;

  return (
    <div className="page">
      <PageHeader title={editing ? `Edit ${existing.data?.name ?? 'product'}` : 'New product'} />
      <Card>
        <form onSubmit={onSubmit} noValidate>
          <FormRow>
            <Field label="SKU" required error={show('sku')}>
              <input value={draft.sku} maxLength={64} onChange={(e) => set('sku', e.target.value.toUpperCase())} />
            </Field>
            <Field label="Name" required error={show('name')}>
              <input value={draft.name} maxLength={200} onChange={(e) => set('name', e.target.value)} />
            </Field>
          </FormRow>
          <Field label="Description">
            <textarea value={draft.description} maxLength={4000} rows={4} onChange={(e) => set('description', e.target.value)} />
          </Field>
          <Field label="Image path" hint="A photo served by this site, e.g. /products/yoga-mat.jpg" error={show('imageUrl')}>
            <input value={draft.imageUrl} maxLength={300} placeholder="/products/..." onChange={(e) => set('imageUrl', e.target.value)} />
          </Field>
          <FormRow>
            <Field label={`Price (${STORE_CURRENCY}, excl. tax)`} required error={show('price')}>
              <input inputMode="decimal" value={draft.price} onChange={(e) => set('price', e.target.value)} />
            </Field>
            <Field label="Category">
              <select value={draft.categoryId} onChange={(e) => set('categoryId', e.target.value)}>
                <option value="">No category</option>
                {(categories.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </Field>
          </FormRow>
          {!editing && (
            <FormRow>
              <Field label="Opening stock" hint="Units already in the warehouse" error={show('initialStock')}>
                <input type="number" min={0} value={draft.initialStock} onChange={(e) => set('initialStock', e.target.value)} />
              </Field>
              <Field label={`Unit cost (${STORE_CURRENCY})`} hint="What one unit cost you; values the opening stock" error={show('unitCost')}>
                <input inputMode="decimal" value={draft.unitCost} onChange={(e) => set('unitCost', e.target.value)} />
              </Field>
            </FormRow>
          )}
          {editing && <p className="muted small">Stock changes go through Inventory (adjustments, purchase receipts) so the ledger stays exact.</p>}
          <label className="checkbox">
            <input type="checkbox" checked={draft.active} onChange={(e) => set('active', e.target.checked)} />
            Visible in the shop
          </label>
          <ProblemAlert error={error} title="Not saved" />
          <FormActions>
            <Button variant="ghost" onClick={() => navigate('/backoffice/catalog/products')}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" busy={busy}>
              {editing ? 'Save' : 'Create product'}
            </Button>
          </FormActions>
        </form>
      </Card>
    </div>
  );
}
