import { type FormEvent, useState } from 'react';
import { catalogApi } from '../../../api/endpoints';
import { errorMessage } from '../../../api/errors';
import type { Category } from '../../../api/types';
import { ProblemAlert } from '../../../components/ui/Alert';
import { Button } from '../../../components/ui/Button';
import { DataTable } from '../../../components/ui/DataTable';
import { useDialogs } from '../../../components/ui/Dialogs';
import { Field, FormActions } from '../../../components/ui/Field';
import { Card, PageHeader } from '../../../components/ui/Layout';
import { PageLoading } from '../../../components/ui/Spinner';
import { useToasts } from '../../../components/ui/Toasts';
import { useAsync } from '../../../hooks/useAsync';
import { useDocumentTitle } from '../../../hooks/misc';
import { blankToNull } from '../../../lib/validation';

export function CategoriesPage() {
  useDocumentTitle('Categories');
  const categories = useAsync((signal) => catalogApi.listCategories(signal), []);
  const { confirm } = useDialogs();
  const toasts = useToasts();
  const [editing, setEditing] = useState<Category | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const startEdit = (c: Category | null) => {
    setEditing(c);
    setName(c?.name ?? '');
    setDescription(c?.description ?? '');
    setError(null);
  };

  const onSave = async (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const body = { name: name.trim(), description: blankToNull(description) };
      if (editing) await catalogApi.updateCategory(editing.id, body);
      else await catalogApi.createCategory(body);
      toasts.success(editing ? 'Category saved' : 'Category created');
      startEdit(null);
      categories.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const onDelete = async (c: Category) => {
    if (!(await confirm({ title: `Delete ${c.name}?`, message: 'Only empty categories can be deleted.', confirmLabel: 'Delete', danger: true }))) return;
    try {
      await catalogApi.deleteCategory(c.id);
      toasts.success('Category deleted');
      categories.reload();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  };

  return (
    <div className="page">
      <PageHeader title="Categories" />
      <div className="grid-2">
        <Card title="All categories">
          {categories.loading && !categories.data && <PageLoading />}
          <ProblemAlert error={categories.error} />
          {categories.data && (
            <DataTable
              rows={categories.data}
              rowKey={(c) => c.id}
              empty="No categories yet."
              columns={[
                { key: 'name', header: 'Name', cell: (c) => c.name },
                { key: 'slug', header: 'Slug', cell: (c) => <code>{c.slug}</code> },
                {
                  key: 'actions',
                  header: '',
                  align: 'right',
                  cell: (c) => (
                    <span className="row-actions">
                      <Button size="sm" variant="ghost" onClick={() => startEdit(c)}>
                        Edit
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => onDelete(c)}>
                        Delete
                      </Button>
                    </span>
                  ),
                },
              ]}
            />
          )}
        </Card>
        <Card title={editing ? `Edit ${editing.name}` : 'New category'}>
          <form onSubmit={onSave}>
            <Field label="Name" required>
              <input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field label="Description">
              <textarea value={description} maxLength={1000} rows={3} onChange={(e) => setDescription(e.target.value)} />
            </Field>
            <ProblemAlert error={error} title="Not saved" />
            <FormActions>
              {editing && (
                <Button variant="ghost" onClick={() => startEdit(null)}>
                  Cancel
                </Button>
              )}
              <Button type="submit" variant="primary" busy={busy} disabled={!name.trim()}>
                {editing ? 'Save' : 'Create'}
              </Button>
            </FormActions>
          </form>
        </Card>
      </div>
    </div>
  );
}
