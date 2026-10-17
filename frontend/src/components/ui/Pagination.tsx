import { Button } from './Button';

interface PaginationProps {
  page: number;
  totalPages: number;
  totalItems?: number;
  onChange: (page: number) => void;
}

export function Pagination({ page, totalPages, totalItems, onChange }: PaginationProps) {
  if (totalPages <= 1) {
    return totalItems !== undefined ? <p className="pagination-summary">{totalItems} item(s)</p> : null;
  }
  return (
    <nav className="pagination" aria-label="Pagination">
      <Button size="sm" variant="ghost" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        Previous
      </Button>
      <span className="pagination-summary">
        Page {page + 1} of {totalPages}
        {totalItems !== undefined && ` · ${totalItems} item(s)`}
      </span>
      <Button size="sm" variant="ghost" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </Button>
    </nav>
  );
}
