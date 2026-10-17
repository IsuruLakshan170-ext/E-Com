import { useState } from 'react';
import { catalogApi } from '../../api/endpoints';
import type { Product } from '../../api/types';
import { useAsync } from '../../hooks/useAsync';
import { useDebounced } from '../../hooks/misc';
import { formatMoney } from '../../lib/format';
import { keepEnterInSearch } from './picker';

interface ProductPickerProps {
  onSelect: (product: Product) => void;
  /** Only staff with catalog:write may search inactive products. */
  includeInactive?: boolean;
  placeholder?: string;
}

/** Search-as-you-type product chooser backed by the public catalog search. */
export function ProductPicker({ onSelect, includeInactive = false, placeholder = 'Search by name or SKU' }: ProductPickerProps) {
  const [text, setText] = useState('');
  const query = useDebounced(text.trim(), 250);
  const results = useAsync(
    (signal) =>
      query.length < 2
        ? Promise.resolve([] as Product[])
        : catalogApi.searchProducts({ q: query, includeInactive: includeInactive || undefined, size: 8 }, signal).then((page) => page.items),
    [query, includeInactive],
  );

  return (
    <div className="picker">
      <input type="search" value={text} placeholder={placeholder} aria-label="Find product" onChange={(e) => setText(e.target.value)} onKeyDown={keepEnterInSearch} />
      {query.length >= 2 && (
        <ul className="picker-results" role="listbox" aria-label="Products">
          {results.loading && <li className="muted">Searching…</li>}
          {!results.loading && (results.data ?? []).length === 0 && <li className="muted">No matching products</li>}
          {(results.data ?? []).map((p) => (
            <li key={p.id}>
              <button
                type="button"
                className="picker-option"
                onClick={() => {
                  onSelect(p);
                  setText('');
                }}
              >
                <span>
                  {p.name} <span className="muted">({p.sku})</span>
                </span>
                <span className="muted">
                  {formatMoney(p.price)} · {p.availableQuantity} available{p.active ? '' : ' · inactive'}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
