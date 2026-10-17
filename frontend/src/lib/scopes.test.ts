import { describe, expect, it } from 'vitest';
import { isStaff, visibleNav } from './scopes';

const CUSTOMER = new Set(['profile:read', 'cart:read', 'cart:write', 'orders:read', 'orders:write']);
const FINANCE = new Set(['profile:read', 'finance:read', 'finance:write', 'purchasing:read', 'inventory:read', 'audit:read']);
const SERVICE = new Set(['profile:read', 'orders:admin', 'returns:manage']);

describe('back-office navigation follows scopes', () => {
  it('customers have no back office', () => {
    expect(isStaff(CUSTOMER)).toBe(false);
    expect(visibleNav(CUSTOMER)).toEqual([]);
  });

  it('a finance manager sees the returns desk (to refund), inventory, purchasing, finance and the audit log, but not users', () => {
    const sections = visibleNav(FINANCE);
    expect(sections.map((s) => s.title)).toEqual(['Sales', 'Inventory', 'Purchasing', 'Finance', 'Administration']);
    expect(sections.find((s) => s.title === 'Sales')?.items.map((i) => i.label)).toEqual(['Returns']);
    expect(sections.find((s) => s.title === 'Administration')?.items.map((i) => i.label)).toEqual(['Audit log']);
  });

  it('customer service sees orders and returns only', () => {
    expect(visibleNav(SERVICE).map((s) => s.title)).toEqual(['Sales']);
  });
});
