import type { ReactNode } from 'react';
import { Route, Routes } from 'react-router-dom';
import { RequireAuth, RequireScope } from './auth/guards';
import { AppShell } from './components/layout/AppShell';
import { BackofficeLayout } from './components/layout/BackofficeLayout';
import { RETURN_STAFF_SCOPES, SCOPES, isStaff } from './lib/scopes';
import { useAuth } from './auth/AuthContext';
import { NotFoundPage, ForbiddenPage } from './pages/ErrorPages';
import { AccountPage } from './pages/account/AccountPage';
import { ApiKeyActivityPage } from './pages/account/ApiKeyActivityPage';
import { ApiKeysPage } from './pages/account/ApiKeysPage';
import { LoginPage } from './pages/account/LoginPage';
import { MyInvoicePage } from './pages/account/MyInvoicePage';
import { MyOrderPage } from './pages/account/MyOrderPage';
import { MyOrdersPage } from './pages/account/MyOrdersPage';
import { MyReturnPage } from './pages/account/MyReturnPage';
import { MyReturnsPage } from './pages/account/MyReturnsPage';
import { PendingApprovalsPage } from './pages/account/PendingApprovalsPage';
import { RegisterPage } from './pages/account/RegisterPage';
import { DashboardPage } from './pages/backoffice/DashboardPage';
import { UserDetailPage } from './pages/backoffice/admin/UserDetailPage';
import { UsersPage } from './pages/backoffice/admin/UsersPage';
import { AuditPage } from './pages/backoffice/admin/AuditPage';
import { CategoriesPage } from './pages/backoffice/catalog/CategoriesPage';
import { ProductFormPage } from './pages/backoffice/catalog/ProductFormPage';
import { ProductsAdminPage } from './pages/backoffice/catalog/ProductsAdminPage';
import { InvoiceDetailPage, InvoicesPage, PaymentsPage, RefundsPage } from './pages/backoffice/finance/FinanceListsPage';
import { JournalPage, SalesSummaryPage, TrialBalancePage } from './pages/backoffice/finance/LedgerPages';
import { RecordPaymentPage } from './pages/backoffice/finance/RecordPaymentPage';
import { TaxRatesPage } from './pages/backoffice/finance/TaxRatesPage';
import { MovementsPage } from './pages/backoffice/inventory/MovementsPage';
import { ReorderSuggestionsPage } from './pages/backoffice/inventory/ReorderSuggestionsPage';
import { StockPage } from './pages/backoffice/inventory/StockPage';
import { ValuationPage } from './pages/backoffice/inventory/ValuationPage';
import { PurchaseOrderFormPage } from './pages/backoffice/purchasing/PurchaseOrderFormPage';
import { PurchaseOrderPage } from './pages/backoffice/purchasing/PurchaseOrderPage';
import { PurchaseOrdersPage } from './pages/backoffice/purchasing/PurchaseOrdersPage';
import { SupplierFormPage } from './pages/backoffice/purchasing/SupplierFormPage';
import { SuppliersPage } from './pages/backoffice/purchasing/SuppliersPage';
import { AdminOrderPage } from './pages/backoffice/sales/AdminOrderPage';
import { AdminOrdersPage } from './pages/backoffice/sales/AdminOrdersPage';
import { AdminReturnPage } from './pages/backoffice/sales/AdminReturnPage';
import { AdminReturnsPage } from './pages/backoffice/sales/AdminReturnsPage';
import { CartPage } from './pages/store/CartPage';
import { CatalogPage } from './pages/store/CatalogPage';
import { CheckoutPage } from './pages/store/CheckoutPage';
import { ProductPage } from './pages/store/ProductPage';

/** Signed-in + (optionally) one of the scopes. The backend checks every scope again; this is UX. */
function guard(element: ReactNode, anyOf: readonly string[] = []): ReactNode {
  return (
    <RequireAuth>
      <RequireScope anyOf={anyOf}>{element}</RequireScope>
    </RequireAuth>
  );
}

function RequireStaff({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  return user && isStaff(user.scopes) ? <>{children}</> : <ForbiddenPage />;
}

export function App() {
  const S = SCOPES;
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<CatalogPage />} />
        <Route path="products/:id" element={<ProductPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="cart" element={guard(<CartPage />, [S.CART_READ])} />
        <Route path="checkout" element={guard(<CheckoutPage />, [S.CART_WRITE])} />

        <Route path="account" element={guard(<AccountPage />)} />
        <Route path="account/api-keys" element={guard(<ApiKeysPage />)} />
        <Route path="account/api-keys/:id/activity" element={guard(<ApiKeyActivityPage />)} />
        <Route path="account/approvals" element={guard(<PendingApprovalsPage />, [S.ORDERS_WRITE])} />
        <Route path="account/orders" element={guard(<MyOrdersPage />, [S.ORDERS_READ])} />
        <Route path="account/orders/:id" element={guard(<MyOrderPage />, [S.ORDERS_READ])} />
        <Route path="account/orders/:id/invoice" element={guard(<MyInvoicePage />, [S.ORDERS_READ])} />
        <Route path="account/returns" element={guard(<MyReturnsPage />, [S.ORDERS_READ])} />
        <Route path="account/returns/:id" element={guard(<MyReturnPage />, [S.ORDERS_READ])} />

        <Route
          path="backoffice"
          element={
            <RequireAuth>
              <RequireStaff>
                <BackofficeLayout />
              </RequireStaff>
            </RequireAuth>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route path="sales/orders" element={guard(<AdminOrdersPage />, [S.ORDERS_ADMIN])} />
          <Route path="sales/orders/:id" element={guard(<AdminOrderPage />, [S.ORDERS_ADMIN])} />
          <Route path="sales/returns" element={guard(<AdminReturnsPage />, RETURN_STAFF_SCOPES)} />
          <Route path="sales/returns/:id" element={guard(<AdminReturnPage />, RETURN_STAFF_SCOPES)} />

          <Route path="catalog/products" element={guard(<ProductsAdminPage />, [S.CATALOG_WRITE])} />
          <Route path="catalog/products/new" element={guard(<ProductFormPage />, [S.CATALOG_WRITE])} />
          <Route path="catalog/products/:id" element={guard(<ProductFormPage />, [S.CATALOG_WRITE])} />
          <Route path="catalog/categories" element={guard(<CategoriesPage />, [S.CATALOG_WRITE])} />

          <Route path="inventory" element={guard(<StockPage />, [S.INVENTORY_READ])} />
          <Route path="inventory/movements" element={guard(<MovementsPage />, [S.INVENTORY_READ])} />
          <Route path="inventory/reorder" element={guard(<ReorderSuggestionsPage />, [S.INVENTORY_READ])} />
          <Route path="inventory/valuation" element={guard(<ValuationPage />, [S.INVENTORY_READ])} />

          <Route path="purchasing/suppliers" element={guard(<SuppliersPage />, [S.PURCHASING_READ])} />
          <Route path="purchasing/suppliers/new" element={guard(<SupplierFormPage />, [S.PURCHASING_WRITE])} />
          <Route path="purchasing/suppliers/:id" element={guard(<SupplierFormPage />, [S.PURCHASING_READ])} />
          <Route path="purchasing/orders" element={guard(<PurchaseOrdersPage />, [S.PURCHASING_READ])} />
          <Route path="purchasing/orders/new" element={guard(<PurchaseOrderFormPage />, [S.PURCHASING_WRITE])} />
          <Route path="purchasing/orders/:id" element={guard(<PurchaseOrderPage />, [S.PURCHASING_READ])} />
          <Route path="purchasing/orders/:id/edit" element={guard(<PurchaseOrderFormPage />, [S.PURCHASING_WRITE])} />

          <Route path="finance/payments/new" element={guard(<RecordPaymentPage />, [S.FINANCE_WRITE])} />
          <Route path="finance/payments" element={guard(<PaymentsPage />, [S.FINANCE_READ])} />
          <Route path="finance/invoices" element={guard(<InvoicesPage />, [S.FINANCE_READ])} />
          <Route path="finance/invoices/:id" element={guard(<InvoiceDetailPage />, [S.FINANCE_READ])} />
          <Route path="finance/refunds" element={guard(<RefundsPage />, [S.FINANCE_READ])} />
          <Route path="finance/tax-rates" element={guard(<TaxRatesPage />, [S.FINANCE_READ])} />
          <Route path="finance/ledger" element={guard(<JournalPage />, [S.FINANCE_READ])} />
          <Route path="finance/trial-balance" element={guard(<TrialBalancePage />, [S.FINANCE_READ])} />
          <Route path="finance/sales-summary" element={guard(<SalesSummaryPage />, [S.FINANCE_READ])} />

          <Route path="admin/users" element={guard(<UsersPage />, [S.USERS_ADMIN])} />
          <Route path="admin/users/:id" element={guard(<UserDetailPage />, [S.USERS_ADMIN])} />
          <Route path="admin/audit" element={guard(<AuditPage />, [S.AUDIT_READ])} />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
