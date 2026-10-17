import { Link, useLocation, useParams } from 'react-router-dom';
import { returnsApi } from '../../api/endpoints';
import { ReturnDetails } from '../../components/domain/ReturnDetails';
import { Alert, ProblemAlert } from '../../components/ui/Alert';
import { StatusBadge } from '../../components/ui/Badge';
import { PageHeader } from '../../components/ui/Layout';
import { PageLoading } from '../../components/ui/Spinner';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';

const NEXT_STEP: Record<string, string> = {
  REQUESTED: 'We are reviewing your request.',
  APPROVED: 'Approved. Please send the items back; we refund once they arrive.',
  RECEIVED: 'We received the items. Your refund is being processed.',
  REFUNDED: 'Refunded to your original payment method.',
  REJECTED: 'This return was not approved.',
};

export function MyReturnPage() {
  const { id = '' } = useParams();
  const created = (useLocation().state as { created?: boolean } | null)?.created === true;
  const rma = useAsync((signal) => returnsApi.getMine(id, signal), [id]);
  useDocumentTitle(rma.data ? `Return ${rma.data.rmaNumber}` : 'Return');

  if (rma.loading && !rma.data) return <PageLoading />;
  if (!rma.data) return <ProblemAlert error={rma.error} title="Return not found" />;
  const r = rma.data;
  return (
    <div className="page">
      <PageHeader title={`Return ${r.rmaNumber}`} subtitle={<StatusBadge status={r.status} />} actions={<Link to={`/account/orders/${r.orderId}`}>View order</Link>} />
      {created && <Alert tone="success">Return requested. We will let you know when it is approved.</Alert>}
      <Alert tone="info">{NEXT_STEP[r.status] ?? ''}</Alert>
      <ReturnDetails rma={r} />
    </div>
  );
}
