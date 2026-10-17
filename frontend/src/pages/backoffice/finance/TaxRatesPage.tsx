import { type FormEvent, useState } from 'react';
import { financeApi } from '../../../api/endpoints';
import type { TaxRate } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { parsePercentToBasisPoints } from '../../../lib/format';
import { SCOPES } from '../../../lib/scopes';
import { COUNTRY_PATTERN } from '../../../lib/validation';

export function TaxRatesPage() {
  useDocumentTitle('Tax rates');
  const { hasScope } = useAuth();
  const canWrite = hasScope(SCOPES.FINANCE_WRITE);
  const rates = useAsync((signal) => financeApi.taxRates(signal), []);
  const toasts = useToasts();
  const [country, setCountry] = useState('');
  const [name, setName] = useState('');
  const [percent, setPercent] = useState('');
  const [active, setActive] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const basisPoints = parsePercentToBasisPoints(percent);
  const valid = COUNTRY_PATTERN.test(country) && name.trim().length > 0 && basisPoints !== null;

  const edit = (rate: TaxRate) => {
    setCountry(rate.countryCode);
    setName(rate.name);
    setPercent(rate.ratePercent);
    setActive(rate.active);
  };

  const onSave = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid || basisPoints === null) return;
    setBusy(true);
    setError(null);
    try {
      await financeApi.upsertTaxRate(country, { name: name.trim(), rateBasisPoints: basisPoints, active });
      toasts.success(`Tax rate for ${country} saved`);
      rates.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader title="Tax rates" subtitle="Sales tax by delivery country, added once on the order subtotal. Countries without a rate pay no tax." />
      <div className="grid-2">
        <Card title="Rates">
          {rates.loading && !rates.data && <PageLoading />}
          <ProblemAlert error={rates.error} />
          {rates.data && (
            <DataTable
              rows={rates.data}
              rowKey={(r) => r.countryCode}
              empty="No tax rates configured."
              columns={[
                { key: 'country', header: 'Country', cell: (r) => <code>{r.countryCode}</code> },
                { key: 'name', header: 'Name', cell: (r) => r.name },
                { key: 'rate', header: 'Rate', align: 'right', cell: (r) => `${r.ratePercent}%` },
                { key: 'state', header: 'State', cell: (r) => <Badge tone={r.active ? 'success' : 'neutral'}>{r.active ? 'Active' : 'Inactive'}</Badge> },
                { key: 'edit', header: '', align: 'right', cell: (r) => (canWrite ? <Button size="sm" variant="ghost" onClick={() => edit(r)}>Edit</Button> : null) },
              ]}
            />
          )}
        </Card>
        {canWrite && (
          <Card title="Add or change a rate">
            <form onSubmit={onSave}>
              <FormRow>
                <Field label="Country code" required hint="e.g. US">
                  <input value={country} maxLength={2} onChange={(e) => setCountry(e.target.value.toUpperCase())} />
                </Field>
                <Field label="Rate (%)" required hint="0 to 50, up to 2 decimals" error={percent && basisPoints === null ? 'Invalid rate' : null}>
                  <input inputMode="decimal" value={percent} onChange={(e) => setPercent(e.target.value)} />
                </Field>
              </FormRow>
              <Field label="Name" required hint="e.g. VAT or Sales tax">
                <input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
              </Field>
              <label className="checkbox">
                <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
                Active
              </label>
              <ProblemAlert error={error} title="Not saved" />
              <FormActions>
                <Button type="submit" variant="primary" busy={busy} disabled={!valid}>
                  Save rate
                </Button>
              </FormActions>
            </form>
          </Card>
        )}
      </div>
    </div>
  );
}
