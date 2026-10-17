import type { Money as MoneyValue } from '../../api/types';
import { formatMinor, formatMoney } from '../../lib/format';

export function MoneyText({ value, strong = false }: { value: MoneyValue | null | undefined; strong?: boolean }) {
  const text = formatMoney(value);
  return strong ? <strong className="money">{text}</strong> : <span className="money">{text}</span>;
}

/** Raw minor units from the ledger (no Money object), in the store currency. */
export function MinorText({ amountMinor, currency }: { amountMinor: number; currency?: string }) {
  return <span className="money">{formatMinor(amountMinor, currency)}</span>;
}
