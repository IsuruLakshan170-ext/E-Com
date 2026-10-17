export function Spinner({ small = false, label = 'Loading' }: { small?: boolean; label?: string }) {
  return <span className={small ? 'spinner spinner-sm' : 'spinner'} role="status" aria-label={label} />;
}

export function PageLoading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="page-loading">
      <Spinner />
      <span>{label}</span>
    </div>
  );
}
