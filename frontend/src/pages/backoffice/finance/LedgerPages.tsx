import { type FormEvent, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { financeApi } from '../../../api/endpoints';
import type { JournalEntry, LedgerAccount } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, PageHeader, Stat } from '../../../components/ui/Layout';
import { MinorText, MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize, todayUtc } from '../../../lib/format';

const SOURCE_TYPES = ['payment', 'refund', 'order', 'goods_receipt', 'stock_movement', 'customer_return'];

export function JournalPage() {
  useDocumentTitle('Journal');
  const [params, setParams] = useSearchParams();
  const sourceType = params.get('source') ?? undefined;
  const page = Number(params.get('page') ?? '0') || 0;
  const accounts = useAsync((signal) => financeApi.ledgerAccounts(signal), []);
  const entries = useAsync((signal) => financeApi.journalEntries({ sourceType, page, size: 25 }, signal), [sourceType, page]);
  const accountName = (code: string) => (accounts.data ?? []).find((a: LedgerAccount) => a.code === code)?.name ?? code;

  return (
    <div className="page">
      <PageHeader
        title="Journal"
        subtitle="Double-entry postings made automatically by sales, purchasing, stock and refunds. Entries are append-only."
        actions={
          <select aria-label="Source" value={sourceType ?? ''} onChange={(e) => setParams(e.target.value ? { source: e.target.value } : {})}>
            <option value="">All sources</option>
            {SOURCE_TYPES.map((s) => (
              <option key={s} value={s}>
                {humanize(s.toUpperCase())}
              </option>
            ))}
          </select>
        }
      />
      <ProblemAlert error={entries.error} />
      {entries.loading && !entries.data && <PageLoading />}
      {entries.data && (
        <>
          {entries.data.items.length === 0 && <p className="muted">No journal entries.</p>}
          <div className="stack">
            {entries.data.items.map((entry) => (
              <JournalEntryCard key={entry.id} entry={entry} accountName={accountName} />
            ))}
          </div>
          <Pagination page={entries.data.page} totalPages={entries.data.totalPages} onChange={(p) => setParams({ ...(sourceType ? { source: sourceType } : {}), page: String(p) })} />
        </>
      )}
    </div>
  );
}

function JournalEntryCard({ entry, accountName }: { entry: JournalEntry; accountName: (code: string) => string }) {
  return (
    <Card title={`${entry.entryNumber} · ${entry.description}`} actions={<span className="muted small">{formatDateTime(entry.postedAt)}</span>}>
      <DataTable
        rows={entry.lines}
        rowKey={(l) => `${l.accountCode}-${l.debitMinor}-${l.creditMinor}`}
        columns={[
          { key: 'account', header: 'Account', cell: (l) => `${l.accountCode} ${accountName(l.accountCode)}` },
          { key: 'debit', header: 'Debit', align: 'right', cell: (l) => (l.debitMinor ? <MinorText amountMinor={l.debitMinor} /> : '') },
          { key: 'credit', header: 'Credit', align: 'right', cell: (l) => (l.creditMinor ? <MinorText amountMinor={l.creditMinor} /> : '') },
        ]}
      />
    </Card>
  );
}

export function TrialBalancePage() {
  useDocumentTitle('Trial balance');
  const trial = useAsync((signal) => financeApi.trialBalance(signal), []);
  return (
    <div className="page">
      <PageHeader title="Trial balance" subtitle="Total debits must equal total credits." actions={<Button onClick={trial.reload}>Refresh</Button>} />
      <ProblemAlert error={trial.error} />
      {trial.loading && !trial.data && <PageLoading />}
      {trial.data && (
        <>
          <Card>
            <div className="stats">
              <Stat label="Total debits" value={<MinorText amountMinor={trial.data.totalDebitMinor} />} />
              <Stat label="Total credits" value={<MinorText amountMinor={trial.data.totalCreditMinor} />} />
              <Stat label="Status" value={<Badge tone={trial.data.balanced ? 'success' : 'danger'}>{trial.data.balanced ? 'Balanced' : 'NOT balanced'}</Badge>} />
            </div>
          </Card>
          <DataTable
            rows={trial.data.accounts}
            rowKey={(a) => a.accountCode}
            columns={[
              { key: 'code', header: 'Account', cell: (a) => `${a.accountCode} ${a.accountName}` },
              { key: 'type', header: 'Type', cell: (a) => humanize(a.type) },
              { key: 'debit', header: 'Debits', align: 'right', cell: (a) => <MinorText amountMinor={a.debitMinor} /> },
              { key: 'credit', header: 'Credits', align: 'right', cell: (a) => <MinorText amountMinor={a.creditMinor} /> },
              { key: 'balance', header: 'Balance', align: 'right', cell: (a) => <strong><MinorText amountMinor={a.balanceMinor} /></strong> },
            ]}
          />
        </>
      )}
    </div>
  );
}

export function SalesSummaryPage() {
  useDocumentTitle('Sales summary');
  const today = todayUtc();
  const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
  const [to, setTo] = useState(today);
  const [range, setRange] = useState({ from: `${today.slice(0, 8)}01`, to: today });
  const summary = useAsync((signal) => financeApi.salesSummary(range.from, range.to, signal), [range.from, range.to]);

  const onApply = (event: FormEvent) => {
    event.preventDefault();
    setRange({ from, to });
  };

  return (
    <div className="page">
      <PageHeader title="Sales summary" subtitle="Dates are UTC; up to one year." />
      <Card>
        <form onSubmit={onApply}>
          <FormRow>
            <Field label="From">
              <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            </Field>
            <Field label="To">
              <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
            </Field>
          </FormRow>
          <FormActions>
            <Button type="submit">Show</Button>
          </FormActions>
        </form>
      </Card>
      <ProblemAlert error={summary.error} />
      {summary.loading && !summary.data && <PageLoading />}
      {summary.data && (
        <Card title={`${summary.data.from} to ${summary.data.to}`}>
          <div className="stats">
            <Stat label="Invoices" value={summary.data.invoicesIssued} />
            <Stat label="Gross sales (excl. tax)" value={<MoneyText value={summary.data.grossSales} />} />
            <Stat label="Tax invoiced" value={<MoneyText value={summary.data.taxInvoiced} />} />
            <Stat label="Refunds" value={summary.data.refundsIssued} hint={<MoneyText value={summary.data.refundedTotal} />} />
            <Stat label="Net sales" value={<MoneyText value={summary.data.netSales} strong />} />
          </div>
        </Card>
      )}
    </div>
  );
}
