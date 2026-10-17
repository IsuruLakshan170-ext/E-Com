import { type FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { accountApi } from '../../api/endpoints';
import { errorMessage } from '../../api/errors';
import type { ApiKey, CreatedApiKey } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Alert, ProblemAlert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { CopyButton } from '../../components/ui/CopyButton';
import { DataTable } from '../../components/ui/DataTable';
import { useDialogs } from '../../components/ui/Dialogs';
import { Field, FormActions, FormRow } from '../../components/ui/Field';
import { Card, PageHeader } from '../../components/ui/Layout';
import { Modal } from '../../components/ui/Modal';
import { PageLoading } from '../../components/ui/Spinner';
import { useToasts } from '../../components/ui/Toasts';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle, useIdempotencyKey } from '../../hooks/misc';
import { STORE_CURRENCY, formatDate, formatDateTime, formatMinor, parseAmountToMinor } from '../../lib/format';

/**
 * Personal API keys, e.g. for the MCP server / an AI shopping assistant. A key acts as you, with at most the scopes
 * you pick (never more than your role). The secret is shown exactly once.
 */
export function ApiKeysPage() {
  useDocumentTitle('API keys');
  const { user } = useAuth();
  const keys = useAsync(() => accountApi.listApiKeys(), []);
  const { confirm } = useDialogs();
  const toasts = useToasts();
  const idem = useIdempotencyKey();
  const grantable = Array.from(user?.scopes ?? []).sort();
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>([]);
  const [days, setDays] = useState(90);
  // Agent guardrails (backend REVIEW.md §5.3): blank means "no limit", same as the backend's null.
  const [maxOrderValue, setMaxOrderValue] = useState('');
  const [maxOrdersPerDay, setMaxOrdersPerDay] = useState('');
  const [approvalAbove, setApprovalAbove] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [created, setCreated] = useState<CreatedApiKey | null>(null);

  const toggle = (scope: string) => setScopes((current) => (current.includes(scope) ? current.filter((s) => s !== scope) : [...current, scope]));

  const onCreate = async (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim() || scopes.length === 0) {
      setError(new Error('Give the key a name and at least one scope.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await accountApi.createApiKey(
        {
          name: name.trim(),
          scopes,
          expiresInDays: days,
          maxOrderValueMinor: maxOrderValue.trim() ? parseAmountToMinor(maxOrderValue) : null,
          maxOrdersPerDay: maxOrdersPerDay.trim() ? Number(maxOrdersPerDay) : null,
          requireApprovalAboveMinor: approvalAbove.trim() ? parseAmountToMinor(approvalAbove) : null,
        },
        idem.key,
      );
      setCreated(result.data);
      setName('');
      setScopes([]);
      setMaxOrderValue('');
      setMaxOrdersPerDay('');
      setApprovalAbove('');
      idem.renew();
      keys.reload();
    } catch (e) {
      setError(e); // retrying reuses the same key, so a double click can never create two keys
    } finally {
      setBusy(false);
    }
  };

  const onRevoke = async (key: ApiKey) => {
    const ok = await confirm({
      title: `Revoke "${key.name}"?`,
      message: 'Anything using this key stops working immediately. This cannot be undone.',
      confirmLabel: 'Revoke key',
      danger: true,
    });
    if (!ok) return;
    try {
      await accountApi.revokeApiKey(key.id);
      toasts.success('Key revoked');
      keys.reload();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  if (user?.actorType !== 'USER') {
    return <Alert tone="warning">API keys can only be managed from a normal sign-in.</Alert>;
  }

  return (
    <div className="page">
      <PageHeader title="API keys" subtitle="Let an assistant or integration act for you, with only the permissions you choose." />
      <div className="grid-2">
        <Card title="Create a key">
          <form onSubmit={onCreate}>
            <Field label="Name" required hint="Where it is used, e.g. 'Shopping assistant'">
              <input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
            </Field>
            <fieldset className="fieldset">
              <legend>Permissions (scopes)</legend>
              <div className="checkbox-grid">
                {grantable.map((scope) => (
                  <label key={scope} className="checkbox">
                    <input type="checkbox" checked={scopes.includes(scope)} onChange={() => toggle(scope)} />
                    <code>{scope}</code>
                  </label>
                ))}
              </div>
            </fieldset>
            <FormRow>
              <Field label="Expires after (days)" hint="1 to 365">
                <input type="number" min={1} max={365} value={days} onChange={(e) => setDays(Math.max(1, Math.min(365, Number(e.target.value) || 90)))} />
              </Field>
            </FormRow>
            <fieldset className="fieldset">
              <legend>Spending guardrails (optional)</legend>
              <p className="muted small">Only apply to orders THIS key places for an agent; leave blank for no limit.</p>
              <FormRow>
                <Field label={`Max order value (${STORE_CURRENCY})`} hint="Orders above this are refused">
                  <input inputMode="decimal" value={maxOrderValue} onChange={(e) => setMaxOrderValue(e.target.value)} />
                </Field>
                <Field label="Max orders per day" hint="Rolling 24 hours">
                  <input type="number" min={0} value={maxOrdersPerDay} onChange={(e) => setMaxOrdersPerDay(e.target.value)} />
                </Field>
              </FormRow>
              <Field label={`Require my approval above (${STORE_CURRENCY})`} hint="Order is placed, but can't be paid until you approve it">
                <input inputMode="decimal" value={approvalAbove} onChange={(e) => setApprovalAbove(e.target.value)} />
              </Field>
            </fieldset>
            <ProblemAlert error={error} title="Key not created" />
            <FormActions>
              <Button type="submit" variant="primary" busy={busy}>
                Create key
              </Button>
            </FormActions>
          </form>
        </Card>
        <Card title="Your keys">
          {keys.loading && !keys.data && <PageLoading />}
          <ProblemAlert error={keys.error} />
          {keys.data && (
            <DataTable
              rows={keys.data}
              rowKey={(k) => k.id}
              empty="No keys yet."
              columns={[
                { key: 'name', header: 'Name', cell: (k) => <>{k.name} <code className="muted">{k.prefix}…</code></> },
                { key: 'scopes', header: 'Scopes', cell: (k) => k.scopes.join(', ') },
                { key: 'limits', header: 'Limits', cell: (k) => <LimitsSummary limits={k.limits} /> },
                { key: 'expires', header: 'Expires', cell: (k) => formatDate(k.expiresAt) },
                { key: 'used', header: 'Last used', cell: (k) => formatDateTime(k.lastUsedAt) },
                { key: 'state', header: 'State', cell: (k) => <Badge tone={k.active ? 'success' : 'neutral'}>{k.active ? 'Active' : 'Revoked / expired'}</Badge> },
                {
                  key: 'actions',
                  header: '',
                  align: 'right',
                  cell: (k) => (
                    <>
                      <Link className="btn btn-ghost btn-sm" to={`/account/api-keys/${k.id}/activity`}>
                        Activity
                      </Link>{' '}
                      {k.active && (
                        <Button size="sm" variant="ghost" onClick={() => onRevoke(k)}>
                          Revoke
                        </Button>
                      )}
                    </>
                  ),
                },
              ]}
            />
          )}
        </Card>
      </div>

      <Modal open={created !== null} title="Your new API key" onClose={() => setCreated(null)} footer={<Button variant="primary" onClick={() => setCreated(null)}>I have stored it</Button>}>
        {created?.apiKey ? (
          <>
            <Alert tone="warning">{created.warning}</Alert>
            <div className="secret">
              <code>{created.apiKey}</code>
              <CopyButton text={created.apiKey} />
            </div>
            <p className="muted small">Send it as the X-API-Key header. Keep it in a password manager or secret store; anyone with it can act as you.</p>
          </>
        ) : (
          <Alert tone="info">{created?.warning ?? 'This key was already created; its secret cannot be shown again.'}</Alert>
        )}
      </Modal>
    </div>
  );
}

function LimitsSummary({ limits }: { limits: ApiKey['limits'] }) {
  const parts: string[] = [];
  if (limits.maxOrderValueMinor != null) parts.push(`max ${formatMinor(limits.maxOrderValueMinor)}/order`);
  if (limits.maxOrdersPerDay != null) parts.push(`max ${limits.maxOrdersPerDay}/day`);
  if (limits.requireApprovalAboveMinor != null) parts.push(`approval above ${formatMinor(limits.requireApprovalAboveMinor)}`);
  return parts.length > 0 ? <>{parts.join(', ')}</> : <span className="muted">No limit</span>;
}
