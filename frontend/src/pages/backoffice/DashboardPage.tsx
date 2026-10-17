import { Link } from 'react-router-dom';
import { financeApi, inventoryApi, ordersApi, purchasingApi, returnsApi } from '../../api/endpoints';
import type { TrialBalance } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/ui/Badge';
import { Card, PageHeader, Stat } from '../../components/ui/Layout';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';
import { RETURN_STAFF_SCOPES, SCOPES } from '../../lib/scopes';

function useCount(enabled: boolean, load: (signal: AbortSignal) => Promise<{ totalItems: number }>): string {
  const state = useAsync((signal) => (enabled ? load(signal) : Promise.resolve({ totalItems: -1 })), [enabled]);
  if (!enabled) return '';
  if (state.error) return '!';
  return state.data ? String(state.data.totalItems) : '…';
}

export function DashboardPage() {
  useDocumentTitle('Back office');
  const { user, hasScope, hasAnyScope } = useAuth();
  const orders = hasScope(SCOPES.ORDERS_ADMIN);
  const returnsStaff = hasAnyScope(RETURN_STAFF_SCOPES);
  const inventory = hasScope(SCOPES.INVENTORY_READ);
  const purchasing = hasScope(SCOPES.PURCHASING_READ);
  const finance = hasScope(SCOPES.FINANCE_READ);
  const financeWrite = hasScope(SCOPES.FINANCE_WRITE);

  const awaitingPayment = useCount(orders, (s) => ordersApi.adminList({ status: 'PENDING_PAYMENT', size: 1 }, s));
  const toShip = useCount(orders, (s) => ordersApi.adminList({ status: 'PAID', size: 1 }, s));
  const returnsRequested = useCount(returnsStaff, (s) => returnsApi.adminList({ status: 'REQUESTED', size: 1 }, s));
  const returnsToReceive = useCount(returnsStaff, (s) => returnsApi.adminList({ status: 'APPROVED', size: 1 }, s));
  const lowStock = useCount(inventory, (s) => inventoryApi.reorderSuggestions({ size: 1 }, s));
  const toApprove = useCount(purchasing, (s) => purchasingApi.listOrders({ status: 'SUBMITTED', size: 1 }, s));
  const toReceive = useCount(purchasing, (s) => purchasingApi.listOrders({ status: 'APPROVED', size: 1 }, s));
  const toCollect = useCount(finance, (s) => financeApi.orders({ size: 1 }, s)); // BE-1: PENDING_PAYMENT by default
  const trial = useAsync<TrialBalance | null>((s) => (finance ? financeApi.trialBalance(s) : Promise.resolve(null)), [finance]);

  return (
    <div className="page">
      <PageHeader title={`Welcome, ${user?.profile.fullName ?? ''}`} subtitle="What needs attention today" />
      <div className="dashboard-grid">
        {orders && (
          <Card title="Orders">
            <div className="stats">
              <Stat label="Awaiting payment" value={awaitingPayment} />
              <Stat label="Paid, to ship" value={toShip} />
            </div>
            <Link to="/backoffice/sales/orders?status=PAID">Ship paid orders</Link>
          </Card>
        )}
        {returnsStaff && (
          <Card title="Returns">
            <div className="stats">
              <Stat label="To review" value={returnsRequested} />
              <Stat label="Approved, awaiting goods" value={returnsToReceive} />
            </div>
            <Link to="/backoffice/sales/returns?status=REQUESTED">Review returns</Link>
          </Card>
        )}
        {inventory && (
          <Card title="Inventory">
            <div className="stats">
              <Stat label="Products to reorder" value={lowStock} />
            </div>
            <Link to="/backoffice/inventory/reorder">See reorder suggestions</Link>
          </Card>
        )}
        {purchasing && (
          <Card title="Purchasing">
            <div className="stats">
              <Stat label="Waiting for approval" value={toApprove} />
              <Stat label="Approved, to receive" value={toReceive} />
            </div>
            <Link to="/backoffice/purchasing/orders?status=SUBMITTED">Open purchase orders</Link>
          </Card>
        )}
        {finance && (
          <Card title="Finance">
            <div className="stats">
              <Stat label="Awaiting payment" value={toCollect} />
              <Stat
                label="Ledger"
                value={trial.data ? <Badge tone={trial.data.balanced ? 'success' : 'danger'}>{trial.data.balanced ? 'Balanced' : 'NOT balanced'}</Badge> : '…'}
              />
            </div>
            {financeWrite && (
              <>
                <Link to="/backoffice/finance/payments/new">Record payments</Link>
                {' · '}
              </>
            )}
            <Link to="/backoffice/finance/trial-balance">Trial balance</Link>
          </Card>
        )}
      </div>
    </div>
  );
}
