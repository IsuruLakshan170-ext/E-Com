import { useState } from 'react';
import { purchasingApi } from '../../api/endpoints';
import type { Supplier, UUID } from '../../api/types';
import { useAsync } from '../../hooks/useAsync';
import { useDebounced } from '../../hooks/misc';
import { Button } from '../ui/Button';
import { keepEnterInSearch } from './picker';

interface SupplierPickerProps {
  value: UUID | '';
  onChange: (id: UUID | '') => void;
  /** The supplier is optional (e.g. a product's preferred supplier): offer "Clear". */
  allowNone?: boolean;
}

/**
 * F5: search-as-you-type over active suppliers (searchSuppliers?q=, by name or code), so it works however many
 * suppliers there are. Use it inside <Field group>. The chosen supplier's name is loaded once when the value came
 * from the server.
 */
export function SupplierPicker({ value, onChange, allowNone = false }: SupplierPickerProps) {
  const [picked, setPicked] = useState<Supplier | null>(null);
  const [searching, setSearching] = useState(false);
  const [text, setText] = useState('');
  const query = useDebounced(text.trim(), 250);
  const showSearch = searching || !value;

  const chosen = useAsync<Supplier | null>(
    (signal) => (!value || picked?.id === value ? Promise.resolve(null) : purchasingApi.getSupplier(value, signal)),
    [value],
  );
  const results = useAsync(
    (signal) =>
      showSearch
        ? purchasingApi.searchSuppliers({ q: query || undefined, size: 8 }, signal).then((page) => page.items)
        : Promise.resolve([] as Supplier[]),
    [showSearch, query],
  );
  const selected = picked?.id === value ? picked : chosen.data?.id === value ? chosen.data : null;

  const choose = (supplier: Supplier | null) => {
    setPicked(supplier);
    setSearching(false);
    setText('');
    onChange(supplier?.id ?? '');
  };

  if (!showSearch) {
    return (
      <div className="picker-chosen">
        <span>
          {selected ? (
            <>
              {selected.name} <span className="muted">({selected.code})</span>
            </>
          ) : chosen.error ? (
            'Supplier not found'
          ) : (
            'Loading…'
          )}
        </span>
        <Button size="sm" variant="ghost" onClick={() => setSearching(true)}>
          Change
        </Button>
        {allowNone && (
          <Button size="sm" variant="ghost" onClick={() => choose(null)}>
            Clear
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="picker">
      <input
        type="search"
        value={text}
        placeholder="Search suppliers by name or code"
        aria-label="Find supplier"
        onChange={(e) => setText(e.target.value)}
        onKeyDown={keepEnterInSearch}
      />
      <ul className="picker-results" role="listbox" aria-label="Suppliers">
        {results.loading && <li className="muted">Searching…</li>}
        {!results.loading && (results.data ?? []).length === 0 && <li className="muted">No matching suppliers</li>}
        {(results.data ?? []).map((s) => (
          <li key={s.id}>
            <button type="button" className="picker-option" onClick={() => choose(s)}>
              <span>
                {s.name} <span className="muted">({s.code})</span>
              </span>
              {s.email && <span className="muted">{s.email}</span>}
            </button>
          </li>
        ))}
      </ul>
      {value && (
        <Button size="sm" variant="ghost" onClick={() => setSearching(false)}>
          Keep the current supplier
        </Button>
      )}
    </div>
  );
}
