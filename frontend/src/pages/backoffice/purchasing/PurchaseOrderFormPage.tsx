import { type FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { purchasingApi } from '../../../api/endpoints';
import type { Product, PurchaseOrder, UUID } from '../../../api/types';
import { ProductPicker } from '../../../components/domain/ProductPicker';
import { SupplierPicker } from '../../../components/domain/SupplierPicker';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle, useIdempotencyKey } from '../../../hooks/misc';
import { STORE_CURRENCY, formatMinor, minorToInput, parseAmountToMinor } from '../../../lib/format';
import { blankToNull } from '../../../lib/validation';

interface LineDraft {
  productId: UUID;
  label: string;
  quantity: string;
  unitCost: string;
}

export function PurchaseOrderFormPage() {
  const { id } = useParams();
  const editing = Boolean(id);
  useDocumentTitle(editing ? 'Edit purchase order' : 'New purchase order');
  const navigate = useNavigate();
  const toasts = useToasts();
  const idem = useIdempotencyKey();
  const existing = useAsync<PurchaseOrder | null>((signal) => (id ? purchasingApi.getOrder(id, signal) : Promise.resolve(null)), [id]);
  const [supplierId, setSupplierId] = useState<UUID | ''>('');
  const [expectedDate, setExpectedDate] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([]);
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const po = existing.data;
    if (!po) return;
    setSupplierId(po.supplierId);
    setExpectedDate(po.expectedDate ?? '');
    setNotes(po.notes ?? '');
    setLines(po.lines.map((l) => ({ productId: l.productId, label: `${l.productName} (${l.sku})`, quantity: String(l.quantityOrdered), unitCost: minorToInput(l.unitCost.amountMinor, l.unitCost.currency) })));
  }, [existing.data]);

  if (editing && existing.loading && !existing.data) return <PageLoading />;
  if (editing && !existing.data) return <ProblemAlert error={existing.error} title="Purchase order not found" />;
  if (existing.data && existing.data.status !== 'DRAFT') return <ProblemAlert error={new Error('Only draft purchase orders can be edited.')} />;

  const addProduct = (p: Product) => {
    if (lines.some((l) => l.productId === p.id)) return;
    setLines([...lines, { productId: p.id, label: `${p.name} (${p.sku})`, quantity: '1', unitCost: '' }]);
    idem.renew();
  };
  const updateLine = (productId: UUID, change: Partial<LineDraft>) => {
    setLines(lines.map((l) => (l.productId === productId ? { ...l, ...change } : l)));
    idem.renew();
  };
  const removeLine = (productId: UUID) => {
    setLines(lines.filter((l) => l.productId !== productId));
    idem.renew(); // a different body is a different request: never reuse the key for it
  };

  const parsed = lines.map((l) => ({ productId: l.productId, quantity: Number(l.quantity), unitCostMinor: parseAmountToMinor(l.unitCost) }));
  const lineProblems = parsed.map((l) =>
    !Number.isInteger(l.quantity) || l.quantity < 1 ? 'Quantity must be at least 1' : l.unitCostMinor === null ? `Unit cost in ${STORE_CURRENCY}, e.g. 4.50` : null,
  );
  const total = parsed.reduce((sum, l) => sum + (l.unitCostMinor ?? 0) * (Number.isInteger(l.quantity) ? l.quantity : 0), 0);
  const formProblem = !supplierId ? 'Choose a supplier' : lines.length === 0 ? 'Add at least one product' : null;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (formProblem || lineProblems.some(Boolean)) return;
    setBusy(true);
    setError(null);
    try {
      const body = {
        supplierId: supplierId as UUID,
        expectedDate: expectedDate || null,
        notes: blankToNull(notes),
        lines: parsed.map((l) => ({ productId: l.productId, quantity: l.quantity, unitCostMinor: l.unitCostMinor as number })),
      };
      const saved = id ? await purchasingApi.updateDraft(id, body) : (await purchasingApi.createOrder(body, idem.key)).data;
      toasts.success(id ? 'Draft saved' : 'Draft purchase order created');
      navigate(`/backoffice/purchasing/orders/${saved.id}`, { replace: true });
    } catch (e) {
      setError(e); // create: a retry reuses the same key, so no duplicate purchase order (M5)
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader title={editing ? `Edit ${existing.data?.poNumber ?? ''}` : 'New purchase order'} subtitle="Saved as a draft; submit it for approval when ready." />
      <form onSubmit={onSubmit} noValidate>
        <Card title="Order">
          <FormRow>
            <Field label="Supplier" required group error={touched && !supplierId ? 'Choose a supplier' : null}>
              <SupplierPicker
                value={supplierId}
                onChange={(value) => {
                  setSupplierId(value);
                  idem.renew();
                }}
              />
            </Field>
            <Field label="Expected delivery">
              <input
                type="date"
                value={expectedDate}
                min={new Date().toISOString().slice(0, 10)}
                onChange={(e) => {
                  setExpectedDate(e.target.value);
                  idem.renew();
                }}
              />
            </Field>
          </FormRow>
          <Field label="Notes">
            <textarea
              value={notes}
              maxLength={1000}
              rows={2}
              onChange={(e) => {
                setNotes(e.target.value);
                idem.renew();
              }}
            />
          </Field>
        </Card>
        <Card title="Lines">
          <ProductPicker includeInactive={false} onSelect={addProduct} placeholder="Add a product by name or SKU" />
          {lines.length > 0 && (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">Product</th>
                    <th scope="col" className="align-right">Quantity</th>
                    <th scope="col" className="align-right">Unit cost ({STORE_CURRENCY})</th>
                    <th scope="col" />
                  </tr>
                </thead>
                <tbody>
                  {lines.map((l, index) => (
                    <tr key={l.productId}>
                      <td>
                        {l.label}
                        {touched && lineProblems[index] && <span className="field-error"> {lineProblems[index]}</span>}
                      </td>
                      <td className="align-right">
                        <input className="input-sm" type="number" min={1} value={l.quantity} aria-label={`Quantity for ${l.label}`} onChange={(e) => updateLine(l.productId, { quantity: e.target.value })} />
                      </td>
                      <td className="align-right">
                        <input className="input-sm" inputMode="decimal" value={l.unitCost} aria-label={`Unit cost for ${l.label}`} onChange={(e) => updateLine(l.productId, { unitCost: e.target.value })} />
                      </td>
                      <td className="align-right">
                        <Button size="sm" variant="ghost" onClick={() => removeLine(l.productId)}>
                          Remove
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="summary-row">
            <span>Estimated total</span>
            <strong>{formatMinor(total)}</strong>
          </p>
        </Card>
        {touched && formProblem && <p className="field-error">{formProblem}</p>}
        <ProblemAlert error={error} title="Not saved" />
        <FormActions>
          <Button variant="ghost" onClick={() => navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" busy={busy}>
            {editing ? 'Save draft' : 'Create draft'}
          </Button>
        </FormActions>
      </form>
    </div>
  );
}
