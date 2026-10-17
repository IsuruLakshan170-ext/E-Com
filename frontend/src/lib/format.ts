import type { Money } from '../api/types';

export const STORE_CURRENCY = import.meta.env.VITE_STORE_CURRENCY || 'USD';

const digitsCache = new Map<string, number>();

/** Decimal places of an ISO currency (USD 2, JPY 0, KWD 3), same rule as the backend's Money. */
export function currencyDigits(currency: string): number {
  const cached = digitsCache.get(currency);
  if (cached !== undefined) {
    return cached;
  }
  let digits = 2;
  try {
    digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    digits = 2;
  }
  digitsCache.set(currency, digits);
  return digits;
}

/** Display only (never used for arithmetic): exact integer minor units -> localized currency text. */
export function formatMinor(amountMinor: number, currency: string = STORE_CURRENCY): string {
  const digits = currencyDigits(currency);
  const major = amountMinor / 10 ** digits;
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(major);
  } catch {
    return `${major.toFixed(digits)} ${currency}`;
  }
}

export function formatMoney(money: Money | null | undefined): string {
  return money ? formatMinor(money.amountMinor, money.currency) : '—';
}

/**
 * Parses an amount typed by a person ("19.99") into exact minor units (1999) using string arithmetic only,
 * so no floating-point rounding can sneak into a price. Returns null for anything that isn't a plain amount.
 */
export function parseAmountToMinor(input: string, currency: string = STORE_CURRENCY): number | null {
  const digits = currencyDigits(currency);
  const text = input.trim();
  const pattern = digits > 0 ? new RegExp(`^(\\d{1,13})(?:\\.(\\d{0,${digits}}))?$`) : /^(\d{1,15})$/;
  const match = pattern.exec(text);
  if (!match) {
    return null;
  }
  const whole = Number(match[1]);
  const fraction = digits > 0 ? Number((match[2] ?? '').padEnd(digits, '0')) : 0;
  const value = whole * 10 ** digits + fraction;
  return Number.isSafeInteger(value) ? value : null;
}

/** Exact minor units -> "19.99" for an input field. */
export function minorToInput(amountMinor: number, currency: string = STORE_CURRENCY): string {
  const digits = currencyDigits(currency);
  if (digits === 0) {
    return String(amountMinor);
  }
  const sign = amountMinor < 0 ? '-' : '';
  const padded = String(Math.abs(amountMinor)).padStart(digits + 1, '0');
  return `${sign}${padded.slice(0, -digits)}.${padded.slice(-digits)}`;
}

/** "10.5" (percent) -> 1050 basis points; null if invalid or above 50 %. */
export function parsePercentToBasisPoints(input: string): number | null {
  const match = /^(\d{1,2})(?:\.(\d{0,2}))?$/.exec(input.trim());
  if (!match) {
    return null;
  }
  const bp = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  return bp <= 5000 ? bp : null;
}

export function basisPointsToPercent(bp: number): string {
  return `${(bp / 100).toFixed(2).replace(/\.?0+$/, '')}%`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso.length === 10 ? `${iso}T00:00:00` : iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleDateString();
}

/** "PENDING_PAYMENT" -> "Pending payment" */
export function humanize(constant: string | null | undefined): string {
  if (!constant) return '—';
  const words = constant.toLowerCase().split('_');
  return [words[0].charAt(0).toUpperCase() + words[0].slice(1), ...words.slice(1)].join(' ');
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : '—';
}

/** Today in UTC as YYYY-MM-DD (the backend's reports use UTC dates). */
export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}
