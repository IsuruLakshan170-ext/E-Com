import { type FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { accountApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Alert, ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { useDialogs } from '../../components/ui/Dialogs';
import { Field, FormActions } from '../../components/ui/Field';
import { Card, KeyValueList, PageHeader } from '../../components/ui/Layout';
import { useDocumentTitle } from '../../hooks/misc';
import { formatDate, humanize } from '../../lib/format';
import { SCOPES } from '../../lib/scopes';
import { passwordProblem } from '../../lib/validation';

export function AccountPage() {
  useDocumentTitle('Your account');
  const { user, hasScope, endSession } = useAuth();
  const { confirm } = useDialogs();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [revokeError, setRevokeError] = useState<unknown>(null);

  if (!user) return null;
  const { profile } = user;
  const nextIssue = passwordProblem(next, profile.email) ?? (next === current && next ? 'Choose a password you have not used here.' : null);
  const repeatIssue = repeat !== next ? 'The passwords do not match.' : null;

  const onChangePassword = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (nextIssue || repeatIssue || !current) return;
    setBusy(true);
    setError(null);
    try {
      await accountApi.changePassword({ currentPassword: current, newPassword: next });
      // The backend revoked every session, access token and API key: sign in again with the new password.
      endSession('revoked');
      navigate('/login', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const onRevokeAll = async () => {
    const ok = await confirm({
      title: 'Sign out everywhere?',
      message: 'This signs you out on every device and permanently revokes all your API keys (for example the one your shopping assistant uses).',
      confirmLabel: 'Sign out everywhere',
      danger: true,
    });
    if (!ok) return;
    setRevokeError(null);
    try {
      await accountApi.revokeAllCredentials();
      endSession('revoked');
      navigate('/login', { replace: true });
    } catch (e) {
      setRevokeError(e);
    }
  };

  return (
    <div className="page">
      <PageHeader title="Your account" />
      <div className="grid-2">
        <Card title="Profile">
          <KeyValueList
            items={[
              { label: 'Name', value: profile.fullName },
              { label: 'Email', value: profile.email },
              { label: 'Role', value: humanize(profile.role) },
              { label: 'Member since', value: formatDate(profile.createdAt) },
            ]}
          />
          <div className="link-list">
            {hasScope(SCOPES.ORDERS_READ) && <Link to="/account/orders">Your orders</Link>}
            {hasScope(SCOPES.ORDERS_READ) && <Link to="/account/returns">Your returns</Link>}
            {hasScope(SCOPES.ORDERS_WRITE) && <Link to="/account/approvals">Approvals for agent-placed orders</Link>}
            <Link to="/account/api-keys">API keys for assistants and integrations</Link>
          </div>
        </Card>
        <Card title="Change password">
          <form onSubmit={onChangePassword} noValidate>
            <Field label="Current password" required error={touched && !current ? 'Required' : null}>
              <input type="password" autoComplete="current-password" value={current} maxLength={72} onChange={(e) => setCurrent(e.target.value)} />
            </Field>
            <Field label="New password" required hint="At least 12 characters." error={touched ? nextIssue : null}>
              <input type="password" autoComplete="new-password" value={next} maxLength={72} onChange={(e) => setNext(e.target.value)} />
            </Field>
            <Field label="Repeat new password" required error={touched ? repeatIssue : null}>
              <input type="password" autoComplete="new-password" value={repeat} maxLength={72} onChange={(e) => setRepeat(e.target.value)} />
            </Field>
            <Alert tone="info">Changing your password signs you out everywhere and revokes your API keys.</Alert>
            <ProblemAlert error={error} title="Password not changed" />
            <FormActions>
              <Button type="submit" variant="primary" busy={busy}>
                Change password
              </Button>
            </FormActions>
          </form>
        </Card>
        <Card title="Security">
          <p>Lost a device, or think someone else has access? Sign out of every session and revoke every API key at once.</p>
          <ProblemAlert error={revokeError} />
          <Button variant="danger" onClick={onRevokeAll}>
            Sign out everywhere
          </Button>
        </Card>
      </div>
    </div>
  );
}
