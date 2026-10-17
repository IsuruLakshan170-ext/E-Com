import { api, request } from './client';
import type {
  AddCartItemRequest, AdminUser, ApiKey, AuditEvent, Cart, CartQuote, Category, CategoryRequest, ChangePasswordRequest,
  CheckoutRequest, CreateApiKeyRequest, CreateProductRequest, CreateReturnRequest, CreatedApiKey, DraftsFromSuggestions,
  FinanceOrder, GoodsReceipt, GoodsReceiptRequest, InventoryItem, InventoryValuation, Invoice, JournalEntry, LedgerAccount, LoginRequest,
  MovementType, Order, OrderStatus, Page, Payment, Product, ProductSort, PurchaseOrder, PurchaseOrderRequest,
  PurchaseOrderStatus, RecordPaymentRequest, Refund, RegisterRequest, ReorderPolicyRequest, ReorderSuggestion,
  ReturnDecisionRequest, ReturnRequest, ReturnStatus, Role, RoleInfo, SalesSummary, StockAdjustmentRequest, StockMovement,
  Supplier, SupplierRequest, TaxRate, TaxRateRequest, TokenResponse, TrialBalance, UUID, UpdateProductRequest, UserProfile,
  WhoAmI,
} from './types';

const enc = encodeURIComponent;

export interface Paging {
  page?: number;
  size?: number;
}

// ---------- auth & account ----------
export const authApi = {
  login: (body: LoginRequest) => api<TokenResponse>('/api/v1/auth/login', { method: 'POST', body, auth: false }),
  register: (body: RegisterRequest) => api<TokenResponse>('/api/v1/auth/register', { method: 'POST', body, auth: false }),
  logout: (refreshToken: string) =>
    api<void>('/api/v1/auth/logout', { method: 'POST', body: { refreshToken }, auth: false }),
  whoAmI: () => api<WhoAmI>('/api/v1/auth/whoami'),
};

export const accountApi = {
  profile: () => api<UserProfile>('/api/v1/users/me'),
  changePassword: (body: ChangePasswordRequest) => api<void>('/api/v1/users/me/password', { method: 'PUT', body }),
  revokeAllCredentials: () => api<void>('/api/v1/users/me/revoke-all-credentials', { method: 'POST' }),
  listApiKeys: () => api<ApiKey[]>('/api/v1/api-keys'),
  createApiKey: (body: CreateApiKeyRequest, idempotencyKey: string) =>
    request<CreatedApiKey>('/api/v1/api-keys', { method: 'POST', body, idempotencyKey }),
  revokeApiKey: (id: UUID) => api<void>(`/api/v1/api-keys/${enc(id)}`, { method: 'DELETE' }),
  /** "What did my agent do?": every audited action taken by this key (backend REVIEW.md §5.3). */
  apiKeyActivity: (id: UUID, query: Paging, signal?: AbortSignal) =>
    api<Page<AuditEvent>>(`/api/v1/api-keys/${enc(id)}/activity`, { query: { ...query }, signal }),
};

// ---------- catalog (public reads) ----------
export interface ProductQuery extends Paging {
  q?: string;
  categoryId?: UUID;
  includeInactive?: boolean;
  sort?: ProductSort;
}

export const catalogApi = {
  searchProducts: (query: ProductQuery, signal?: AbortSignal) =>
    api<Page<Product>>('/api/v1/products', { query: { ...query }, signal }),
  getProduct: (id: UUID, includeInactive = false, signal?: AbortSignal) =>
    api<Product>(`/api/v1/products/${enc(id)}`, { query: { includeInactive: includeInactive || undefined }, signal }),
  createProduct: (body: CreateProductRequest) => api<Product>('/api/v1/products', { method: 'POST', body }),
  updateProduct: (id: UUID, body: UpdateProductRequest) => api<Product>(`/api/v1/products/${enc(id)}`, { method: 'PUT', body }),
  deactivateProduct: (id: UUID) => api<void>(`/api/v1/products/${enc(id)}`, { method: 'DELETE' }),
  listCategories: (signal?: AbortSignal) => api<Category[]>('/api/v1/categories', { signal }),
  createCategory: (body: CategoryRequest) => api<Category>('/api/v1/categories', { method: 'POST', body }),
  updateCategory: (id: UUID, body: CategoryRequest) => api<Category>(`/api/v1/categories/${enc(id)}`, { method: 'PUT', body }),
  deleteCategory: (id: UUID) => api<void>(`/api/v1/categories/${enc(id)}`, { method: 'DELETE' }),
};

// ---------- cart & checkout ----------
export const cartApi = {
  get: () => api<Cart>('/api/v1/cart'),
  quote: (countryCode: string) => api<CartQuote>('/api/v1/cart/quote', { query: { countryCode } }),
  addItem: (body: AddCartItemRequest) => api<Cart>('/api/v1/cart/items', { method: 'POST', body }),
  setQuantity: (productId: UUID, quantity: number) =>
    api<Cart>(`/api/v1/cart/items/${enc(productId)}`, { method: 'PUT', body: { quantity } }),
  removeItem: (productId: UUID) => api<Cart>(`/api/v1/cart/items/${enc(productId)}`, { method: 'DELETE' }),
  clear: () => api<void>('/api/v1/cart', { method: 'DELETE' }),
};

export const ordersApi = {
  /** Same Idempotency-Key + same body on retry => the original order comes back (replayed). */
  placeOrder: (body: CheckoutRequest, idempotencyKey: string) =>
    request<Order>('/api/v1/orders', { method: 'POST', body, idempotencyKey }),
  listMine: (query: Paging & { status?: OrderStatus }, signal?: AbortSignal) =>
    api<Page<Order>>('/api/v1/orders', { query: { ...query }, signal }),
  getMine: (id: UUID, signal?: AbortSignal) => api<Order>(`/api/v1/orders/${enc(id)}`, { signal }),
  cancelMine: (id: UUID) => api<Order>(`/api/v1/orders/${enc(id)}/cancel`, { method: 'POST' }),
  myInvoice: (id: UUID, signal?: AbortSignal) => api<Invoice>(`/api/v1/orders/${enc(id)}/invoice`, { signal }),
  /** orderNumber: exact, case-insensitive (BE-2). */
  adminList: (query: Paging & { status?: OrderStatus; orderNumber?: string }, signal?: AbortSignal) =>
    api<Page<Order>>('/api/v1/admin/orders', { query: { ...query }, signal }),
  adminGet: (id: UUID, signal?: AbortSignal) => api<Order>(`/api/v1/admin/orders/${enc(id)}`, { signal }),
  adminUpdateStatus: (id: UUID, status: OrderStatus) =>
    api<Order>(`/api/v1/admin/orders/${enc(id)}/status`, { method: 'PATCH', body: { status } }),
  /** Orders an agent placed above its key's threshold; the owner must approve before they can be paid. */
  listAwaitingApproval: (query: Paging, signal?: AbortSignal) =>
    api<Page<Order>>('/api/v1/orders/awaiting-approval', { query: { ...query }, signal }),
  approve: (id: UUID) => api<Order>(`/api/v1/orders/${enc(id)}/approve`, { method: 'POST' }),
  reject: (id: UUID) => api<Order>(`/api/v1/orders/${enc(id)}/reject`, { method: 'POST' }),
};

// ---------- returns ----------
export const returnsApi = {
  request: (orderId: UUID, body: CreateReturnRequest, idempotencyKey: string) =>
    request<ReturnRequest>(`/api/v1/orders/${enc(orderId)}/returns`, { method: 'POST', body, idempotencyKey }),
  listMine: (query: Paging, signal?: AbortSignal) => api<Page<ReturnRequest>>('/api/v1/returns', { query: { ...query }, signal }),
  getMine: (id: UUID, signal?: AbortSignal) => api<ReturnRequest>(`/api/v1/returns/${enc(id)}`, { signal }),
  adminList: (query: Paging & { status?: ReturnStatus }, signal?: AbortSignal) =>
    api<Page<ReturnRequest>>('/api/v1/admin/returns', { query: { ...query }, signal }),
  adminGet: (id: UUID, signal?: AbortSignal) => api<ReturnRequest>(`/api/v1/admin/returns/${enc(id)}`, { signal }),
  approve: (id: UUID, body: ReturnDecisionRequest) =>
    api<ReturnRequest>(`/api/v1/admin/returns/${enc(id)}/approve`, { method: 'POST', body }),
  reject: (id: UUID, body: ReturnDecisionRequest) =>
    api<ReturnRequest>(`/api/v1/admin/returns/${enc(id)}/reject`, { method: 'POST', body }),
  receive: (id: UUID, sodOverrideReason?: string) =>
    api<ReturnRequest>(`/api/v1/admin/returns/${enc(id)}/receive`, { method: 'POST', body: { sodOverrideReason: sodOverrideReason ?? null } }),
  refund: (id: UUID, sodOverrideReason?: string) =>
    api<ReturnRequest>(`/api/v1/admin/returns/${enc(id)}/refund`, { method: 'POST', body: { sodOverrideReason: sodOverrideReason ?? null } }),
};

// ---------- inventory ----------
export const inventoryApi = {
  getItem: (productId: UUID, signal?: AbortSignal) => api<InventoryItem>(`/api/v1/inventory/products/${enc(productId)}`, { signal }),
  adjust: (productId: UUID, body: StockAdjustmentRequest, idempotencyKey: string) =>
    request<InventoryItem>(`/api/v1/inventory/products/${enc(productId)}/adjustments`, { method: 'POST', body, idempotencyKey }),
  setReorderPolicy: (productId: UUID, body: ReorderPolicyRequest) =>
    api<InventoryItem>(`/api/v1/inventory/products/${enc(productId)}/reorder-policy`, { method: 'PUT', body }),
  movements: (query: Paging & { productId?: UUID; type?: MovementType }, signal?: AbortSignal) =>
    api<Page<StockMovement>>('/api/v1/inventory/movements', { query: { ...query }, signal }),
  reorderSuggestions: (query: Paging, signal?: AbortSignal) =>
    api<Page<ReorderSuggestion>>('/api/v1/inventory/reorder-suggestions', { query: { ...query }, signal }),
  valuation: (query: Paging, signal?: AbortSignal) =>
    api<InventoryValuation>('/api/v1/inventory/valuation', { query: { ...query }, signal }),
};

// ---------- purchasing ----------
export const purchasingApi = {
  searchSuppliers: (query: Paging & { q?: string; includeInactive?: boolean }, signal?: AbortSignal) =>
    api<Page<Supplier>>('/api/v1/purchasing/suppliers', { query: { ...query }, signal }),
  getSupplier: (id: UUID, signal?: AbortSignal) => api<Supplier>(`/api/v1/purchasing/suppliers/${enc(id)}`, { signal }),
  createSupplier: (body: SupplierRequest) => api<Supplier>('/api/v1/purchasing/suppliers', { method: 'POST', body }),
  updateSupplier: (id: UUID, body: SupplierRequest) => api<Supplier>(`/api/v1/purchasing/suppliers/${enc(id)}`, { method: 'PUT', body }),
  deactivateSupplier: (id: UUID) => api<void>(`/api/v1/purchasing/suppliers/${enc(id)}`, { method: 'DELETE' }),
  listOrders: (query: Paging & { status?: PurchaseOrderStatus; supplierId?: UUID }, signal?: AbortSignal) =>
    api<Page<PurchaseOrder>>('/api/v1/purchasing/purchase-orders', { query: { ...query }, signal }),
  getOrder: (id: UUID, signal?: AbortSignal) => api<PurchaseOrder>(`/api/v1/purchasing/purchase-orders/${enc(id)}`, { signal }),
  createOrder: (body: PurchaseOrderRequest, idempotencyKey: string) =>
    request<PurchaseOrder>('/api/v1/purchasing/purchase-orders', { method: 'POST', body, idempotencyKey }),
  updateDraft: (id: UUID, body: PurchaseOrderRequest) =>
    api<PurchaseOrder>(`/api/v1/purchasing/purchase-orders/${enc(id)}`, { method: 'PUT', body }),
  submit: (id: UUID) => api<PurchaseOrder>(`/api/v1/purchasing/purchase-orders/${enc(id)}/submit`, { method: 'POST' }),
  approve: (id: UUID, sodOverrideReason?: string) =>
    api<PurchaseOrder>(`/api/v1/purchasing/purchase-orders/${enc(id)}/approve`, { method: 'POST', body: { sodOverrideReason: sodOverrideReason ?? null } }),
  cancel: (id: UUID) => api<PurchaseOrder>(`/api/v1/purchasing/purchase-orders/${enc(id)}/cancel`, { method: 'POST' }),
  receive: (id: UUID, body: GoodsReceiptRequest, idempotencyKey: string) =>
    request<GoodsReceipt>(`/api/v1/purchasing/purchase-orders/${enc(id)}/receipts`, { method: 'POST', body, idempotencyKey }),
  listReceipts: (id: UUID, signal?: AbortSignal) =>
    api<GoodsReceipt[]>(`/api/v1/purchasing/purchase-orders/${enc(id)}/receipts`, { signal }),
  draftsFromSuggestions: () =>
    api<DraftsFromSuggestions>('/api/v1/purchasing/purchase-orders/from-reorder-suggestions', { method: 'POST' }),
};

// ---------- finance ----------
export const financeApi = {
  /** BE-1: status defaults to PENDING_PAYMENT, unless orderNumber is given (then any status). */
  orders: (query: Paging & { status?: OrderStatus; orderNumber?: string }, signal?: AbortSignal) =>
    api<Page<FinanceOrder>>('/api/v1/finance/orders', { query: { ...query }, signal }),
  recordPayment: (orderId: UUID, body: RecordPaymentRequest) =>
    api<Payment>(`/api/v1/finance/orders/${enc(orderId)}/payments`, { method: 'POST', body }),
  payments: (query: Paging, signal?: AbortSignal) => api<Page<Payment>>('/api/v1/finance/payments', { query: { ...query }, signal }),
  invoices: (query: Paging, signal?: AbortSignal) => api<Page<Invoice>>('/api/v1/finance/invoices', { query: { ...query }, signal }),
  invoice: (id: UUID, signal?: AbortSignal) => api<Invoice>(`/api/v1/finance/invoices/${enc(id)}`, { signal }),
  refunds: (query: Paging, signal?: AbortSignal) => api<Page<Refund>>('/api/v1/finance/refunds', { query: { ...query }, signal }),
  taxRates: (signal?: AbortSignal) => api<TaxRate[]>('/api/v1/finance/tax-rates', { signal }),
  upsertTaxRate: (countryCode: string, body: TaxRateRequest) =>
    api<TaxRate>(`/api/v1/finance/tax-rates/${enc(countryCode)}`, { method: 'PUT', body }),
  ledgerAccounts: (signal?: AbortSignal) => api<LedgerAccount[]>('/api/v1/finance/ledger/accounts', { signal }),
  journalEntries: (query: Paging & { sourceType?: string }, signal?: AbortSignal) =>
    api<Page<JournalEntry>>('/api/v1/finance/ledger/journal-entries', { query: { ...query }, signal }),
  trialBalance: (signal?: AbortSignal) => api<TrialBalance>('/api/v1/finance/reports/trial-balance', { signal }),
  salesSummary: (from: string, to: string, signal?: AbortSignal) =>
    api<SalesSummary>('/api/v1/finance/reports/sales-summary', { query: { from, to }, signal }),
};

// ---------- administration ----------
export interface AuditQuery extends Paging {
  action?: string;
  actorId?: string;
  actorType?: string;
  apiKeyId?: string;
  targetType?: string;
  targetId?: string;
  from?: string;
  to?: string;
}

export const adminApi = {
  roles: (signal?: AbortSignal) => api<RoleInfo[]>('/api/v1/admin/roles', { signal }),
  users: (query: Paging & { role?: Role }, signal?: AbortSignal) => api<Page<AdminUser>>('/api/v1/admin/users', { query: { ...query }, signal }),
  user: (id: UUID, signal?: AbortSignal) => api<AdminUser>(`/api/v1/admin/users/${enc(id)}`, { signal }),
  changeRole: (id: UUID, role: Role) => api<AdminUser>(`/api/v1/admin/users/${enc(id)}/role`, { method: 'PATCH', body: { role } }),
  setEnabled: (id: UUID, enabled: boolean) => api<AdminUser>(`/api/v1/admin/users/${enc(id)}/status`, { method: 'PATCH', body: { enabled } }),
  revokeCredentials: (id: UUID) => api<AdminUser>(`/api/v1/admin/users/${enc(id)}/revoke-credentials`, { method: 'POST' }),
  auditEvents: (query: AuditQuery, signal?: AbortSignal) =>
    api<Page<AuditEvent>>('/api/v1/admin/audit-events', { query: { ...query }, signal }),
};
