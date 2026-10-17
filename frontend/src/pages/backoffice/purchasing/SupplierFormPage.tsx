import { type FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { purchasingApi } from '../../../api/endpoints';
import { errorMessage, fieldErrors } from '../../../api/errors';
import type { Supplier } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { useDialogs } from '../../../components/ui/Dialogs';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { SCOPES } from '../../../lib/scopes';
import { SUPPLIER_CODE_PATTERN, blankToNull } from '../../../lib/validation';

export function SupplierFormPage() {
  const { id } = useParams();
  const editing = Boolean(id);
  useDocumentTitle(editing ? 'Supplier' : 'New supplier');
  const { hasScope } = useAuth();
  const canWrite = hasScope(SCOPES.PURCHASING_WRITE);
  const navigate = useNavigate();
  const toasts = useToasts();
  const { confirm } = useDialogs();
  const existing = useAsync<Supplier | null>((signal) => (id ? purchasingApi.getSupplier(id, signal) : Promise.resolve(null)), [id]);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [terms, setTerms] = useState('30');
  const [active, setActive] = useState(true);
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const s = existing.data;
    if (s) {
      setCode(s.code);
      setName(s.name);
      setEmail(s.email ?? '');
      setPhone(s.phone ?? '');
      setAddress(s.address ?? '');
      setTerms(String(s.paymentTermsDays));
      setActive(s.active);
    }
  }, [existing.data]);

  if (editing && existing.loading && !existing.data) return <PageLoading />;
  if (editing && !existing.data) return <ProblemAlert error={existing.error} title="Supplier not found" />;

  const server = fieldErrors(error);
  const termsValue = Number(terms);
  const problems = {
    code: SUPPLIER_CODE_PATTERN.test(code) ? null : '2-32 characters: A-Z, 0-9, - or _',
    name: name.trim() ? null : 'Required',
    terms: Number.isInteger(termsValue) && termsValue >= 0 && termsValue <= 365 ? null : '0 to 365 days',
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (Object.values(problems).some(Boolean)) return;
    setBusy(true);
    setError(null);
    try {
      const body = { code, name: name.trim(), email: blankToNull(email), phone: blankToNull(phone), address: blankToNull(address), paymentTermsDays: termsValue, active };
      const saved = id ? await purchasingApi.updateSupplier(id, body) : await purchasingApi.createSupplier(body);
      toasts.success('Supplier saved');
      navigate(`/backoffice/purchasing/suppliers/${saved.id}`, { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const onDeactivate = async () => {
    if (!id || !(await confirm({ title: 'Deactivate supplier?', message: 'Existing purchase orders keep it; no new ones can use it.', confirmLabel: 'Deactivate', danger: true }))) return;
    try {
      await purchasingApi.deactivateSupplier(id);
      toasts.success('Supplier deactivated');
      existing.reload();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  const show = (key: keyof typeof problems, serverKey: string) => (touched ? problems[key] : null) ?? server[serverKey] ?? null;

  return (
    <div className="page">
      <PageHeader
        title={editing ? existing.data?.name ?? 'Supplier' : 'New supplier'}
        actions={editing && canWrite && existing.data?.active && <Button variant="danger" onClick={onDeactivate}>Deactivate</Button>}
      />
      <Card>
        <form onSubmit={onSubmit} noValidate>
          <fieldset className="fieldset" disabled={!canWrite}>
            <FormRow>
              <Field label="Code" required error={show('code', 'code')}>
                <input value={code} maxLength={32} onChange={(e) => setCode(e.target.value.toUpperCase())} />
              </Field>
              <Field label="Name" required error={show('name', 'name')}>
                <input value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />
              </Field>
            </FormRow>
            <FormRow>
              <Field label="Email" error={server.email}>
                <input type="email" value={email} maxLength={254} onChange={(e) => setEmail(e.target.value)} />
              </Field>
              <Field label="Phone">
                <input value={phone} maxLength={40} onChange={(e) => setPhone(e.target.value)} />
              </Field>
            </FormRow>
            <Field label="Address">
              <textarea value={address} maxLength={500} rows={3} onChange={(e) => setAddress(e.target.value)} />
            </Field>
            <FormRow>
              <Field label="Payment terms (days)" required error={show('terms', 'paymentTermsDays')}>
                <input type="number" min={0} max={365} value={terms} onChange={(e) => setTerms(e.target.value)} />
              </Field>
            </FormRow>
            <label className="checkbox">
              <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
              Active
            </label>
          </fieldset>
          <ProblemAlert error={error} title="Not saved" />
          {canWrite && (
            <FormActions>
              <Button variant="ghost" onClick={() => navigate('/backoffice/purchasing/suppliers')}>
                Back
              </Button>
              <Button type="submit" variant="primary" busy={busy}>
                Save supplier
              </Button>
            </FormActions>
          )}
        </form>
      </Card>
    </div>
  );
}
