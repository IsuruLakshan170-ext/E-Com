import type { KeyboardEvent } from 'react';

/** Enter in a picker's search box must not submit the surrounding form (e.g. create a purchase order early). */
export function keepEnterInSearch(event: KeyboardEvent<HTMLInputElement>): void {
  if (event.key === 'Enter') {
    event.preventDefault();
  }
}
