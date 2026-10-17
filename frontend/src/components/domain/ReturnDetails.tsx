import type { ReturnRequest } from '../../api/types';
import { formatDateTime } from '../../lib/format';
import { DataTable } from '../ui/DataTable';
import { Card, KeyValueList } from '../ui/Layout';
import { MoneyText } from '../ui/Money';

export function ReturnDetails({ rma }: { rma: ReturnRequest }) {
  return (
    <div className="grid-2">
      <Card title="Items">
        <DataTable
          rows={rma.lines}
          rowKey={(l) => l.productId}
          columns={[
            { key: 'name', header: 'Item', cell: (l) => <>{l.productName} <span className="muted">({l.sku})</span></> },
            { key: 'qty', header: 'Qty', align: 'right', cell: (l) => l.quantity },
            { key: 'total', header: 'Value', align: 'right', cell: (l) => <MoneyText value={l.lineTotal} /> },
          ]}
        />
        <p className="summary-row">
          <span>Merchandise value (tax refunded proportionally)</span>
          <MoneyText value={rma.merchandiseTotal} strong />
        </p>
      </Card>
      <Card title="Details">
        <KeyValueList
          items={[
            { label: 'Reason', value: rma.reason },
            { label: 'Resolution note', value: rma.resolutionNote ?? '—' },
            { label: 'Requested', value: formatDateTime(rma.createdAt) },
            { label: 'Last update', value: formatDateTime(rma.updatedAt) },
          ]}
        />
      </Card>
    </div>
  );
}
