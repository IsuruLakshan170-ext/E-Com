import { useState } from 'react';
import { ProblemAlert } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';
import { PageHeader } from '../components/ui/Layout';
import { PageLoading } from '../components/ui/Spinner';
import { useCart } from './CartContext';

/** Shown by the cart and checkout pages until the cart has loaded: a spinner, or the load error with a retry. */
export function CartPending({ title }: { title: string }) {
  const { loadError, refresh } = useCart();
  const [busy, setBusy] = useState(false);

  if (!loadError) {
    return <PageLoading />;
  }

  const retry = async () => {
    setBusy(true);
    try {
      await refresh();
    } catch {
      // still failing: loadError now holds the latest problem
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader title={title} actions={<Button busy={busy} onClick={retry}>Try again</Button>} />
      <ProblemAlert error={loadError} title="Your cart could not be loaded" />
    </div>
  );
}
