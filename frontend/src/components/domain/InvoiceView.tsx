import type { Invoice } from '../../api/types';
import { formatDateTime } from '../../lib/format';
import { AddressBlock, OrderLines, Totals } from './OrderSummary';

/** Printable invoice (the print stylesheet hides navigation). */
export function InvoiceView({ invoice }: { invoice: Invoice }) {
  return (
    <article className="invoice">
      <header className="invoice-header">
        <div>
          <h2>Invoice {invoice.invoiceNumber}</h2>
          <p className="muted">
            Order {invoice.orderNumber} · issued {formatDateTime(invoice.issuedAt)}
          </p>
        </div>
        <div>
          <h3>Bill to</h3>
          <AddressBlock address={invoice.billTo} />
        </div>
      </header>
      <OrderLines lines={invoice.lines} />
      <Totals
        subtotal={invoice.subtotal}
        taxRateBasisPoints={invoice.taxRateBasisPoints}
        tax={invoice.tax}
        total={invoice.total}
        refunded={invoice.refunded}
      />
    </article>
  );
}
