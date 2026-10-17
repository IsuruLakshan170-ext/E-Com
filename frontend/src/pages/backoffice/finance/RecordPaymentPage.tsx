import { type FormEvent, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { financeApi } from '../../../api/endpoints';
import { ApiError } from '../../../api/errors';
import { type FinanceOrder, PAYMENT_METHODS, type Payment, type PaymentMethod } from '../../../api/types';
import { Alert, ProblemAlert } from '../../../components/ui/Alert';
import { Badge, StatusBadge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, KeyValueList, PageHeader } from '../../../components/ui/Layout';
import { MoneyText } from '../../../components/ui/Money';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, formatMinor, humanize, minorToInput, parseAmountToMinor } from '../../../lib/format';
import { ORDER_NUMBER_PATTERN } from '../../../lib/validation';

const OUTCOME_TEXT: Record<string, string> = {
  APPLIED: 'The order is now paid and the invoice was issued.',
  APPLIED_AFTER_REINSTATEMENT: 'The order had expired but its stock was still available, so it is paid again and the invoice was issued.',
  REFUNDED_ORDER_EXPIRED: 'The order had expired and its stock is gone: the payment was recorded and refunded in full (see the credit note).',
  REFUNDED_ORDER_CANCELLED: 'The order was cancelled: the payment was recorded and refunded in full (see the credit note).',
};

/**
 * Records a full payment against an order. Finance picks the order from the payment queue (BE-1: orders awaiting
 * payment) or finds it by the order number the customer quoted as the payment reference (BE-2: any status, so a
 * late payment for an expired order can be booked too). ?orderNumber= (the order page's shortcut) opens it directly.
 */
export function RecordPaymentPage() {
  useDocumentTitle('Record payment');
  const [params, setParams] = useSearchParams();
  const orderNumber = (params.get('orderNumber') ?? '').trim();
  const page = Number(params.get('page') ?? '0') || 0;
  const [search, setSearch] = useState(orderNumber);
  const [selected, setSelected] = useState<FinanceOrder | null>(null);
  const [payment, setPayment] = useState<Payment | null>(null);
  const searchValid = ORDER_NUMBER_PATTERN.test(orderNumber);
  const queue = useAsync(
    (signal) => financeApi.orders({ orderNumber: searchValid ? orderNumber : undefined, page, size: 20 }, signal),
    [orderNumber, page],
  );

  // A search for one order number (or the order page's shortcut) opens that order straight away.
  useEffect(() => {
    if (searchValid && queue.data?.items.length === 1) {
      setSelected(queue.data.items[0]);
    }
  }, [searchValid, queue.data]);

  const onSearch = (event: FormEvent) => {
    event.preventDefault();
    const typed = search.trim();
    setSelected(null);
    setParams(typed ? { orderNumber: typed } : {});
  };

  const backToQueue = () => {
    setSelected(null);
    setPayment(null);
    setSearch('');
    setParams({});
    queue.reload();
  };

  if (payment) {
    return (
      <div className="page">
        <PageHeader title="Payment recorded" subtitle={<StatusBadge status={payment.outcome} />} />
        <Card>
          <Alert tone={payment.outcome.startsWith('REFUNDED') ? 'warning' : 'success'}>{OUTCOME_TEXT[payment.outcome] ?? humanize(payment.outcome)}</Alert>
          <KeyValueList
            items={[
              { label: 'Amount', value: <MoneyText value={payment.amount} /> },
              { label: 'Method', value: humanize(payment.method) },
              { label: 'Reference', value: payment.reference ?? '—' },
              { label: 'Invoice', value: payment.invoiceId ? <Link to={`/backoffice/finance/invoices/${payment.invoiceId}`}>{payment.invoiceNumber}</Link> : '—' },
              { label: 'Credit note', value: payment.creditNoteNumber ?? '—' },
            ]}
          />
          <FormActions>
            <Button onClick={backToQueue}>Record another</Button>
          </FormActions>
        </Card>
      </div>
    );
  }

  if (selected) {
    return (
      <div className="page">
        <PageHeader title={`Record payment for ${selected.orderNumber}`} subtitle="Full payment for one order, e.g. a bank transfer that arrived." />
        <PaymentForm order={selected} onBack={() => setSelected(null)} onRecorded={setPayment} />
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader title="Record payment" subtitle="Pick the order a payment belongs to, or search the order number the customer used as the reference." />
      <form className="toolbar" role="search" onSubmit={onSearch}>
        <input
          type="search"
          value={search}
          placeholder="Order number, e.g. ORD-20261013-ABCD2345"
          aria-label="Order number"
          spellCheck={false}
          onChange={(e) => setSearch(e.target.value)}
        />
        <Button type="submit">Find order</Button>
        {orderNumber && (
          <Button variant="ghost" onClick={backToQueue}>
            Show all awaiting payment
          </Button>
        )}
      </form>
      {orderNumber && !searchValid && <Alert tone="warning">Order numbers contain only letters, digits and dashes, e.g. ORD-20261013-ABCD2345.</Alert>}
      <ProblemAlert error={queue.error} />
      {queue.loading && !queue.data && <PageLoading />}
      {queue.data && (
        <>
          <DataTable
            rows={queue.data.items}
            rowKey={(o) => o.id}
            empty={searchValid ? `No order ${orderNumber.toUpperCase()}.` : 'No orders are waiting for payment.'}
            columns={[
              { key: 'number', header: 'Order', cell: (o) => o.orderNumber },
              { key: 'customer', header: 'Customer', cell: (o) => <CustomerCell order={o} /> },
              { key: 'status', header: 'Status', cell: (o) => <StatusBadge status={o.status} /> },
              { key: 'due', header: 'Pay by', cell: (o) => <DueCell order={o} /> },
              { key: 'total', header: 'Total', align: 'right', cell: (o) => <MoneyText value={o.total} /> },
              {
                key: 'action',
                header: '',
                align: 'right',
                cell: (o) => (
                  <Button size="sm" variant="primary" onClick={() => setSelected(o)}>
                    Record payment
                  </Button>
                ),
              },
            ]}
          />
          <Pagination
            page={queue.data.page}
            totalPages={queue.data.totalPages}
            totalItems={queue.data.totalItems}
            onChange={(p) => setParams({ ...(orderNumber ? { orderNumber } : {}), page: String(p) })}
          />
        </>
      )}
    </div>
  );
}

function CustomerCell({ order }: { order: FinanceOrder }) {
  return (
    <>
      {order.customerName ?? '—'}
      {order.customerEmail && <span className="muted"> {order.customerEmail}</span>}
    </>
  );
}

function DueCell({ order }: { order: FinanceOrder }) {
  if (order.status !== 'PENDING_PAYMENT' || !order.expiresAt) {
    return <>—</>;
  }
  const overdue = Date.parse(order.expiresAt) < Date.now();
  return (
    <>
      {formatDateTime(order.expiresAt)} {overdue && <Badge tone="warning">overdue</Badge>}
    </>
  );
}

/** What recording a payment will do for an order that is not simply awaiting payment (the backend decides). */
function statusNote(order: FinanceOrder): { tone: 'info' | 'warning'; text: string; blocked: boolean } | null {
  if (order.status === 'PENDING_PAYMENT') return null;
  if (order.status === 'CANCELLED' && order.cancelReason === 'EXPIRED') {
    return { tone: 'info', text: 'This order expired. If its stock is still available it is reinstated as paid; otherwise the payment is recorded and refunded in full.', blocked: false };
  }
  if (order.status === 'CANCELLED') {
    return { tone: 'warning', text: 'This order was cancelled: the payment will be recorded and refunded in full.', blocked: false };
  }
  return { tone: 'warning', text: `This order is already paid (${humanize(order.status)}). A second payment is not accepted.`, blocked: true };
}

function PaymentForm({ order, onBack, onRecorded }: { order: FinanceOrder; onBack: () => void; onRecorded: (payment: Payment) => void }) {
  const currency = order.total.currency;
  const [amount, setAmount] = useState(minorToInput(order.total.amountMinor, currency));
  const [method, setMethod] = useState<PaymentMethod>('BANK_TRANSFER');
  const [reference, setReference] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const note = statusNote(order);

  const amountMinor = parseAmountToMinor(amount, currency);
  const expected = error instanceof ApiError && error.code === 'AMOUNT_MISMATCH' && typeof error.details.expectedAmountMinor === 'number'
    ? error.details.expectedAmountMinor
    : null;
  const valid = amountMinor !== null && amountMinor > 0 && !note?.blocked;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid || amountMinor === null) return;
    setBusy(true);
    setError(null);
    try {
      onRecorded(await financeApi.recordPayment(order.id, { method, amountMinor, reference: reference.trim() || null }));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <KeyValueList
        items={[
          { label: 'Order', value: order.orderNumber },
          { label: 'Customer', value: <CustomerCell order={order} /> },
          { label: 'Status', value: <StatusBadge status={order.status} /> },
          { label: 'Order total', value: <MoneyText value={order.total} strong /> },
          { label: 'Pay by', value: <DueCell order={order} /> },
        ]}
      />
      {note && <Alert tone={note.tone}>{note.text}</Alert>}
      <form onSubmit={onSubmit}>
        <FormRow>
          <Field label={`Amount received (${currency})`} required hint="Must equal the order total">
            <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </Field>
          <Field label="Method">
            <select value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
              {PAYMENT_METHODS.map((m) => (
                <option key={m} value={m}>
                  {humanize(m)}
                </option>
              ))}
            </select>
          </Field>
        </FormRow>
        <Field label="Reference" hint="Bank or card reference (letters, digits, spaces, . / : # -)">
          <input value={reference} maxLength={100} onChange={(e) => setReference(e.target.value)} />
        </Field>
        <ProblemAlert error={error} title="Payment not recorded" />
        {expected !== null && (
          <p>
            The order total is <strong>{formatMinor(expected, currency)}</strong>.{' '}
            <Button size="sm" onClick={() => setAmount(minorToInput(expected, currency))}>
              Use this amount
            </Button>
          </p>
        )}
        <FormActions>
          <Button variant="ghost" onClick={onBack}>
            Back
          </Button>
          <Button type="submit" variant="primary" busy={busy} disabled={!valid}>
            Record payment
          </Button>
        </FormActions>
      </form>
    </Card>
  );
}
