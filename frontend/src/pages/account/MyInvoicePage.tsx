import { Link, useParams } from 'react-router-dom';
import { ordersApi } from '../../api/endpoints';
import { InvoiceView } from '../../components/domain/InvoiceView';
import { ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { PageLoading } from '../../components/ui/Spinner';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';

export function MyInvoicePage() {
  const { id = '' } = useParams();
  const invoice = useAsync((signal) => ordersApi.myInvoice(id, signal), [id]);
  useDocumentTitle(invoice.data ? `Invoice ${invoice.data.invoiceNumber}` : 'Invoice');

  if (invoice.loading && !invoice.data) return <PageLoading />;
  if (!invoice.data) return <ProblemAlert error={invoice.error} title="Invoice not available" />;
  return (
    <div className="page">
      <div className="no-print toolbar">
        <Link to={`/account/orders/${id}`}>Back to order</Link>
        <Button onClick={() => window.print()}>Print</Button>
      </div>
      <InvoiceView invoice={invoice.data} />
    </div>
  );
}
