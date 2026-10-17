import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { adminApi } from '../../../api/endpoints';
import { errorMessage } from '../../../api/errors';
import { ROLES, type Role } from '../../../api/types';
import { useAuth } from '../../../auth/AuthContext';
import { Alert, ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { useDialogs } from '../../../components/ui/Dialogs';
import { Field, FormActions } from '../../../components/ui/Field';
import { Card, KeyValueList, PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize } from '../../../lib/format';

export function UserDetailPage() {
  const { id = '' } = useParams();
  const { user: me } = useAuth();
  const target = useAsync((signal) => adminApi.user(id, signal), [id]);
  const roles = useAsync((signal) => adminApi.roles(signal), []);
  const { confirm } = useDialogs();
  const toasts = useToasts();
  const [role, setRole] = useState<Role>('CUSTOMER');
  const [busy, setBusy] = useState(false);
  useDocumentTitle(target.data?.email ?? 'User');
  useEffect(() => {
    if (target.data) setRole(target.data.role);
  }, [target.data]);

  if (target.loading && !target.data) return <PageLoading />;
  if (!target.data) return <ProblemAlert error={target.error} title="User not found" />;
  const u = target.data;
  const isSelf = me?.profile.id === u.id;
  const roleScopes = roles.data?.find((r) => r.role === role)?.scopes ?? [];

  const act = async (title: string, message: string, work: () => Promise<typeof u>, success: string, danger = false) => {
    if (!(await confirm({ title, message, confirmLabel: title, danger }))) return;
    setBusy(true);
    try {
      target.setData(await work());
      toasts.success(success);
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader title={u.fullName} subtitle={u.email} actions={<Link to={`/backoffice/admin/audit?actorId=${u.id}`}>Activity in audit log</Link>} />
      {isSelf && <Alert tone="info">This is your own account. You cannot change your own role or status here.</Alert>}
      <div className="grid-2">
        <Card title="Account">
          <KeyValueList
            items={[
              { label: 'Role', value: humanize(u.role) },
              { label: 'State', value: <Badge tone={u.enabled ? 'success' : 'neutral'}>{u.enabled ? 'Enabled' : 'Disabled'}</Badge> },
              { label: 'Created', value: formatDateTime(u.createdAt) },
              { label: 'Updated', value: formatDateTime(u.updatedAt) },
              { label: 'Scopes', value: u.scopes.join(', ') },
            ]}
          />
          {!isSelf && (
            <FormActions>
              <Button
                variant={u.enabled ? 'danger' : 'primary'}
                busy={busy}
                onClick={() =>
                  act(
                    u.enabled ? 'Disable account' : 'Enable account',
                    u.enabled ? 'The user is signed out everywhere and cannot sign in until re-enabled.' : 'The user can sign in again.',
                    () => adminApi.setEnabled(u.id, !u.enabled),
                    u.enabled ? 'Account disabled' : 'Account enabled',
                    u.enabled,
                  )
                }
              >
                {u.enabled ? 'Disable' : 'Enable'}
              </Button>
              <Button
                variant="danger"
                busy={busy}
                onClick={() =>
                  act(
                    'Revoke all credentials',
                    'Ends every session and access token and revokes all API keys of this user, e.g. after a suspected compromise.',
                    () => adminApi.revokeCredentials(u.id),
                    'All credentials revoked',
                    true,
                  )
                }
              >
                Revoke all credentials
              </Button>
            </FormActions>
          )}
        </Card>
        {!isSelf && (
          <Card title="Role">
            <Field label="Role" hint="Changing the role signs the user out so the new permissions apply immediately.">
              <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {humanize(r)}
                  </option>
                ))}
              </select>
            </Field>
            <p className="muted small">Grants: {roleScopes.join(', ') || '…'}</p>
            <FormActions>
              <Button
                variant="primary"
                busy={busy}
                disabled={role === u.role}
                onClick={() => act('Change role', `Make ${u.email} a ${humanize(role)}?`, () => adminApi.changeRole(u.id, role), 'Role changed')}
              >
                Change role
              </Button>
            </FormActions>
          </Card>
        )}
      </div>
    </div>
  );
}
