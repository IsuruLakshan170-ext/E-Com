import { type FormEvent, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { type AuditQuery, adminApi } from '../../../api/endpoints';
import { ACTOR_TYPES, AUDIT_ACTIONS } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { Field, FormActions, FormRow } from '../../../components/ui/Field';
import { Card, PageHeader } from '../../../components/ui/Layout';
import { Pagination } from '../../../components/ui/Pagination';
import { PageLoading } from '../../../components/ui/Spinner';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { formatDateTime, humanize, shortId } from '../../../lib/format';

const FILTER_KEYS = ['action', 'actorId', 'actorType', 'apiKeyId', 'targetType', 'targetId', 'from', 'to'] as const;
type FilterKey = (typeof FILTER_KEYS)[number];

/** datetime-local value -> ISO instant (the backend expects ISO-8601 instants). */
function toInstant(local: string): string | undefined {
  if (!local) return undefined;
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

export function AuditPage() {
  useDocumentTitle('Audit log');
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? '0') || 0;
  const current: Record<FilterKey, string> = Object.fromEntries(FILTER_KEYS.map((k) => [k, params.get(k) ?? ''])) as Record<FilterKey, string>;
  const [draft, setDraft] = useState<Record<FilterKey, string>>(current);
  const query: AuditQuery = {
    action: current.action || undefined,
    actorId: current.actorId || undefined,
    actorType: current.actorType || undefined,
    apiKeyId: current.apiKeyId || undefined,
    targetType: current.targetType || undefined,
    targetId: current.targetId || undefined,
    from: toInstant(current.from),
    to: toInstant(current.to),
    page,
    size: 50,
  };
  const events = useAsync((signal) => adminApi.auditEvents(query, signal), [params.toString()]);

  const apply = (values: Record<FilterKey, string>) => {
    const next = new URLSearchParams();
    for (const key of FILTER_KEYS) {
      if (values[key]) next.set(key, values[key]);
    }
    setParams(next);
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    apply(draft);
  };

  const quick = (changes: Partial<Record<FilterKey, string>>) => {
    const values = { ...Object.fromEntries(FILTER_KEYS.map((k) => [k, ''])), ...changes } as Record<FilterKey, string>;
    setDraft(values);
    apply(values);
  };

  const set = (key: FilterKey) => (event: { target: { value: string } }) => setDraft({ ...draft, [key]: event.target.value });

  return (
    <div className="page">
      <PageHeader title="Audit log" subtitle="Security and business events. Rows are permanent and cannot be edited." />
      <Card>
        <div className="toolbar">
          <Button size="sm" onClick={() => quick({ action: 'SOD_OVERRIDDEN' })}>
            Separation-of-duties overrides
          </Button>
          <Button size="sm" onClick={() => quick({ action: 'ACCESS_DENIED' })}>
            Access denied
          </Button>
          <Button size="sm" onClick={() => quick({ action: 'LOGIN_FAILED' })}>
            Failed logins
          </Button>
          <Button size="sm" onClick={() => quick({ actorType: 'API_KEY' })}>
            Done by API keys
          </Button>
          <Button size="sm" variant="ghost" onClick={() => quick({})}>
            Clear
          </Button>
        </div>
        <form onSubmit={onSubmit}>
          <FormRow>
            <Field label="Action">
              <select value={draft.action} onChange={set('action')}>
                <option value="">Any</option>
                {AUDIT_ACTIONS.map((a) => (
                  <option key={a} value={a}>
                    {humanize(a)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Actor type">
              <select value={draft.actorType} onChange={set('actorType')}>
                <option value="">Any</option>
                {ACTOR_TYPES.map((a) => (
                  <option key={a} value={a}>
                    {humanize(a)}
                  </option>
                ))}
              </select>
            </Field>
          </FormRow>
          <FormRow>
            <Field label="Actor id">
              <input value={draft.actorId} spellCheck={false} onChange={set('actorId')} />
            </Field>
            <Field label="API key id">
              <input value={draft.apiKeyId} spellCheck={false} onChange={set('apiKeyId')} />
            </Field>
          </FormRow>
          <FormRow>
            <Field label="Target type" hint="e.g. order, return, purchase_order">
              <input value={draft.targetType} maxLength={50} onChange={set('targetType')} />
            </Field>
            <Field label="Target id">
              <input value={draft.targetId} maxLength={64} spellCheck={false} onChange={set('targetId')} />
            </Field>
          </FormRow>
          <FormRow>
            <Field label="From">
              <input type="datetime-local" value={draft.from} onChange={set('from')} />
            </Field>
            <Field label="To">
              <input type="datetime-local" value={draft.to} onChange={set('to')} />
            </Field>
          </FormRow>
          <FormActions>
            <Button type="submit" variant="primary">
              Search
            </Button>
          </FormActions>
        </form>
      </Card>
      <ProblemAlert error={events.error} />
      {events.loading && !events.data && <PageLoading />}
      {events.data && (
        <>
          <DataTable
            rows={events.data.items}
            rowKey={(e) => e.id}
            empty="No matching events."
            columns={[
              { key: 'when', header: 'When', cell: (e) => formatDateTime(e.occurredAt) },
              { key: 'action', header: 'Action', cell: (e) => <Badge tone={e.action === 'SOD_OVERRIDDEN' || e.action === 'ACCESS_DENIED' ? 'warning' : 'neutral'}>{humanize(e.action)}</Badge> },
              { key: 'actor', header: 'Actor', cell: (e) => `${humanize(e.actorType)} ${shortId(e.actorId)}${e.apiKeyId ? ` via key ${shortId(e.apiKeyId)}` : ''}` },
              { key: 'target', header: 'Target', cell: (e) => (e.targetType ? `${e.targetType} ${shortId(e.targetId)}` : '—') },
              { key: 'details', header: 'Details', cell: (e) => <span className="details">{e.details ?? ''}</span> },
              { key: 'ip', header: 'IP', cell: (e) => e.ipAddress ?? '—' },
            ]}
          />
          <Pagination
            page={events.data.page}
            totalPages={events.data.totalPages}
            totalItems={events.data.totalItems}
            onChange={(p) => {
              const next = new URLSearchParams(params);
              next.set('page', String(p));
              setParams(next);
            }}
          />
        </>
      )}
    </div>
  );
}
