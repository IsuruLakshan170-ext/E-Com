/** Scope strings exactly as the backend issues them (JWT "scope" claim / whoami). */
export const SCOPES = {
  PROFILE_READ: 'profile:read',
  CART_READ: 'cart:read',
  CART_WRITE: 'cart:write',
  ORDERS_READ: 'orders:read',
  ORDERS_WRITE: 'orders:write',
  CATALOG_WRITE: 'catalog:write',
  ORDERS_ADMIN: 'orders:admin',
  RETURNS_MANAGE: 'returns:manage',
  INVENTORY_READ: 'inventory:read',
  INVENTORY_WRITE: 'inventory:write',
  PURCHASING_READ: 'purchasing:read',
  PURCHASING_WRITE: 'purchasing:write',
  PURCHASING_APPROVE: 'purchasing:approve',
  FINANCE_READ: 'finance:read',
  FINANCE_WRITE: 'finance:write',
  AUDIT_READ: 'audit:read',
  USERS_ADMIN: 'users:admin',
} as const;

export const RETURN_STAFF_SCOPES: string[] = [SCOPES.RETURNS_MANAGE, SCOPES.INVENTORY_WRITE, SCOPES.FINANCE_WRITE];

export interface NavItem {
  to: string;
  label: string;
  anyOf: string[];
}

export interface NavSection {
  title: string;
  items: NavItem[];
}

/** Back-office navigation. The server enforces every scope; this only hides what a role cannot use. */
export const BACKOFFICE_NAV: NavSection[] = [
  {
    title: 'Sales',
    items: [
      { to: '/backoffice/sales/orders', label: 'Orders', anyOf: [SCOPES.ORDERS_ADMIN] },
      { to: '/backoffice/sales/returns', label: 'Returns', anyOf: RETURN_STAFF_SCOPES },
    ],
  },
  {
    title: 'Catalog',
    items: [
      { to: '/backoffice/catalog/products', label: 'Products', anyOf: [SCOPES.CATALOG_WRITE] },
      { to: '/backoffice/catalog/categories', label: 'Categories', anyOf: [SCOPES.CATALOG_WRITE] },
    ],
  },
  {
    title: 'Inventory',
    items: [
      { to: '/backoffice/inventory', label: 'Stock', anyOf: [SCOPES.INVENTORY_READ] },
      { to: '/backoffice/inventory/movements', label: 'Stock ledger', anyOf: [SCOPES.INVENTORY_READ] },
      { to: '/backoffice/inventory/reorder', label: 'Reorder suggestions', anyOf: [SCOPES.INVENTORY_READ] },
      { to: '/backoffice/inventory/valuation', label: 'Valuation', anyOf: [SCOPES.INVENTORY_READ] },
    ],
  },
  {
    title: 'Purchasing',
    items: [
      { to: '/backoffice/purchasing/orders', label: 'Purchase orders', anyOf: [SCOPES.PURCHASING_READ] },
      { to: '/backoffice/purchasing/suppliers', label: 'Suppliers', anyOf: [SCOPES.PURCHASING_READ] },
    ],
  },
  {
    title: 'Finance',
    items: [
      { to: '/backoffice/finance/payments/new', label: 'Record payment', anyOf: [SCOPES.FINANCE_WRITE] },
      { to: '/backoffice/finance/payments', label: 'Payments', anyOf: [SCOPES.FINANCE_READ] },
      { to: '/backoffice/finance/invoices', label: 'Invoices', anyOf: [SCOPES.FINANCE_READ] },
      { to: '/backoffice/finance/refunds', label: 'Refunds', anyOf: [SCOPES.FINANCE_READ] },
      { to: '/backoffice/finance/tax-rates', label: 'Tax rates', anyOf: [SCOPES.FINANCE_READ] },
      { to: '/backoffice/finance/ledger', label: 'Journal', anyOf: [SCOPES.FINANCE_READ] },
      { to: '/backoffice/finance/trial-balance', label: 'Trial balance', anyOf: [SCOPES.FINANCE_READ] },
      { to: '/backoffice/finance/sales-summary', label: 'Sales summary', anyOf: [SCOPES.FINANCE_READ] },
    ],
  },
  {
    title: 'Administration',
    items: [
      { to: '/backoffice/admin/users', label: 'Users', anyOf: [SCOPES.USERS_ADMIN] },
      { to: '/backoffice/admin/audit', label: 'Audit log', anyOf: [SCOPES.AUDIT_READ] },
    ],
  },
];

export function hasAny(granted: ReadonlySet<string>, required: readonly string[]): boolean {
  return required.length === 0 || required.some((scope) => granted.has(scope));
}

export function visibleNav(granted: ReadonlySet<string>): NavSection[] {
  return BACKOFFICE_NAV
    .map((section) => ({ ...section, items: section.items.filter((item) => hasAny(granted, item.anyOf)) }))
    .filter((section) => section.items.length > 0);
}

/** Anyone with at least one back-office screen. */
export function isStaff(granted: ReadonlySet<string>): boolean {
  return visibleNav(granted).length > 0;
}
