import { Link, useParams, useSearchParams } from 'react-router-dom';
import { financeApi } from '../../../api/endpoints';
import { InvoiceView } from '../../../components/domain/InvoiceView';
import { ProblemAlert } from '../../../components/ui/Alert';
import { StatusBadge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize, shortId } from '../../../lib/format';

function usePage(): [number, (page: number) => void] {
  const [params, setParams] = useSearchParams();
  return [Number(params.get('page') ?? '0') || 0, (page) => setParams({ page: String(page) })];
}

export function PaymentsPage() {
  useDocumentTitle('Payments');
  const [page, setPage] = usePage();
  const payments = useAsync((signal) => financeApi.payments({ page, size: 50 }, signal), [page]);
  return (
    <div className="page">
      <PageHeader title="Payments" actions={<Link className="btn btn-primary" to="/backoffice/finance/payments/new">Record payment</Link>} />
      <ProblemAlert error={payments.error} />
      {payments.loading && !payments.data && <PageLoading />}
      {payments.data && (
        <>
          <DataTable
            rows={payments.data.items}
            rowKey={(p) => p.id}
            empty="No payments yet."
            columns={[
              { key: 'when', header: 'Recorded', cell: (p) => formatDateTime(p.createdAt) },
              { key: 'order', header: 'Order', cell: (p) => <code>{shortId(p.orderId)}</code> },
              { key: 'method', header: 'Method', cell: (p) => humanize(p.method) },
              { key: 'reference', header: 'Reference', cell: (p) => p.reference ?? '—' },
              { key: 'outcome', header: 'Outcome', cell: (p) => <StatusBadge status={p.outcome} /> },
              { key: 'invoice', header: 'Invoice', cell: (p) => (p.invoiceId ? <Link to={`/backoffice/finance/invoices/${p.invoiceId}`}>{p.invoiceNumber}</Link> : '—') },
              { key: 'amount', header: 'Amount', align: 'right', cell: (p) => <MoneyText value={p.amount} /> },
            ]}
          />
          <Pagination page={payments.data.page} totalPages={payments.data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}

export function InvoicesPage() {
  useDocumentTitle('Invoices');
  const [page, setPage] = usePage();
  const invoices = useAsync((signal) => financeApi.invoices({ page, size: 50 }, signal), [page]);
  return (
    <div className="page">
      <PageHeader title="Invoices" subtitle="Issued once, on payment. Corrections are credit notes (refunds), never edits." />
      <ProblemAlert error={invoices.error} />
      {invoices.loading && !invoices.data && <PageLoading />}
      {invoices.data && (
        <>
          <DataTable
            rows={invoices.data.items}
            rowKey={(i) => i.id}
            empty="No invoices yet."
            columns={[
              { key: 'number', header: 'Invoice', cell: (i) => <Link to={`/backoffice/finance/invoices/${i.id}`}>{i.invoiceNumber}</Link> },
              { key: 'issued', header: 'Issued', cell: (i) => formatDateTime(i.issuedAt) },
              { key: 'order', header: 'Order', cell: (i) => i.orderNumber },
              { key: 'billTo', header: 'Bill to', cell: (i) => i.billTo.recipientName },
              { key: 'total', header: 'Total', align: 'right', cell: (i) => <MoneyText value={i.total} /> },
              { key: 'refunded', header: 'Refunded', align: 'right', cell: (i) => (i.refunded.amountMinor > 0 ? <MoneyText value={i.refunded} /> : '—') },
            ]}
          />
          <Pagination page={invoices.data.page} totalPages={invoices.data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}

export function InvoiceDetailPage() {
  const { id = '' } = useParams();
  const invoice = useAsync((signal) => financeApi.invoice(id, signal), [id]);
  useDocumentTitle(invoice.data ? `Invoice ${invoice.data.invoiceNumber}` : 'Invoice');
  if (invoice.loading && !invoice.data) return <PageLoading />;
  if (!invoice.data) return <ProblemAlert error={invoice.error} title="Invoice not found" />;
  return (
    <div className="page">
      <div className="no-print toolbar">
        <Link to="/backoffice/finance/invoices">All invoices</Link>
        <Button onClick={() => window.print()}>Print</Button>
      </div>
      <InvoiceView invoice={invoice.data} />
    </div>
  );
}

export function RefundsPage() {
  useDocumentTitle('Refunds');
  const [page, setPage] = usePage();
  const refunds = useAsync((signal) => financeApi.refunds({ page, size: 50 }, signal), [page]);
  return (
    <div className="page">
      <PageHeader title="Refunds and credit notes" />
      <ProblemAlert error={refunds.error} />
      {refunds.loading && !refunds.data && <PageLoading />}
      {refunds.data && (
        <>
          <DataTable
            rows={refunds.data.items}
            rowKey={(r) => r.id}
            empty="No refunds."
            columns={[
              { key: 'number', header: 'Credit note', cell: (r) => r.creditNoteNumber },
              { key: 'when', header: 'Issued', cell: (r) => formatDateTime(r.createdAt) },
              { key: 'order', header: 'Order', cell: (r) => <code>{shortId(r.orderId)}</code> },
              { key: 'reason', header: 'Reason', cell: (r) => r.reason },
              { key: 'tax', header: 'Of which tax', align: 'right', cell: (r) => <MoneyText value={r.tax} /> },
              { key: 'amount', header: 'Amount', align: 'right', cell: (r) => <MoneyText value={r.amount} /> },
            ]}
          />
          <Pagination page={refunds.data.page} totalPages={refunds.data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}
