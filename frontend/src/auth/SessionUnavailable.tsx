import { useState } from 'react';
import { ProblemAlert } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';
import { FormActions } from '../components/ui/Field';
import { Card, PageHeader } from '../components/ui/Layout';
import { useAuth } from './AuthContext';

/** F2: the server could not be reached (offline, rate-limited, down). The session is kept: retry, or sign out. */
export function SessionUnavailable() {
  const { unavailableError, retry, logout } = useAuth();
  const [busy, setBusy] = useState(false);

  const onRetry = async () => {
    setBusy(true);
    try {
      await retry();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader title="Can't reach the store right now" subtitle="You're still signed in. This page reconnects by itself when you're back online." />
      <Card>
        <ProblemAlert error={unavailableError} title="Connection problem" />
        <FormActions>
          <Button variant="ghost" onClick={() => void logout()}>
            Sign out
          </Button>
          <Button variant="primary" busy={busy} onClick={onRetry}>
            Try again
          </Button>
        </FormActions>
      </Card>
    </div>
  );
}
