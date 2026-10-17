import type { Money, OrderLine, ShippingAddress } from '../../api/types';
import { basisPointsToPercent } from '../../lib/format';
import { DataTable } from '../ui/DataTable';
import { MoneyText } from '../ui/Money';

interface TotalsProps {
  subtotal: Money;
  taxRateBasisPoints: number;
  tax: Money;
  total: Money;
  refunded?: Money;
}

export function OrderLines({ lines }: { lines: OrderLine[] }) {
  return (
    <DataTable
      rows={lines}
      rowKey={(l) => l.productId}
      columns={[
        { key: 'name', header: 'Item', cell: (l) => <>{l.productName} <span className="muted">({l.sku})</span></> },
        { key: 'price', header: 'Unit price', align: 'right', cell: (l) => <MoneyText value={l.unitPrice} /> },
        { key: 'qty', header: 'Qty', align: 'right', cell: (l) => l.quantity },
        { key: 'total', header: 'Line total', align: 'right', cell: (l) => <MoneyText value={l.lineTotal} /> },
      ]}
    />
  );
}

export function Totals({ subtotal, taxRateBasisPoints, tax, total, refunded }: TotalsProps) {
  return (
    <dl className="totals">
      <div>
        <dt>Subtotal</dt>
        <dd><MoneyText value={subtotal} /></dd>
      </div>
      <div>
        <dt>Tax ({basisPointsToPercent(taxRateBasisPoints)})</dt>
        <dd><MoneyText value={tax} /></dd>
      </div>
      <div className="totals-grand">
        <dt>Total</dt>
        <dd><MoneyText value={total} strong /></dd>
      </div>
      {refunded && refunded.amountMinor > 0 && (
        <div className="totals-refunded">
          <dt>Refunded</dt>
          <dd>−<MoneyText value={refunded} /></dd>
        </div>
      )}
    </dl>
  );
}

export function AddressBlock({ address }: { address: ShippingAddress }) {
  return (
    <address className="address">
      {address.recipientName}
      <br />
      {address.line1}
      {address.line2 && (
        <>
          <br />
          {address.line2}
        </>
      )}
      <br />
      {address.postalCode} {address.city}
      {address.region ? `, ${address.region}` : ''}
      <br />
      {address.countryCode}
    </address>
  );
}
