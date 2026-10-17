import { type FormEvent, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { Alert, ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { Field, FormActions } from '../../components/ui/Field';
import { Card } from '../../components/ui/Layout';
import { useDocumentTitle } from '../../hooks/misc';
import { safeNextPath } from '../../lib/validation';

export function LoginPage() {
  useDocumentTitle('Sign in');
  const { status, login, notice, clearNotice } = useAuth();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const next = safeNextPath(params.get('next'));
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  if (status === 'authenticated') {
    return <Navigate to={next} replace />;
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      clearNotice();
      navigate(next, { replace: true });
    } catch (e) {
      setError(e);
      setPassword('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page page-narrow">
      <Card title="Sign in">
        {notice && <Alert tone="info">{notice}</Alert>}
        <form onSubmit={onSubmit}>
          <Field label="Email" required>
            <input type="email" autoComplete="username" value={email} maxLength={254} required onChange={(e) => setEmail(e.target.value)} />
          </Field>
          <Field label="Password" required>
            <input type="password" autoComplete="current-password" value={password} maxLength={72} required onChange={(e) => setPassword(e.target.value)} />
          </Field>
          {/* Every failure looks the same on purpose (no account enumeration). */}
          <ProblemAlert error={error} title="Could not sign you in" />
          <FormActions>
            <Button type="submit" variant="primary" busy={busy}>
              Sign in
            </Button>
          </FormActions>
        </form>
        <p className="muted">
          New here? <Link to={`/register?next=${encodeURIComponent(next)}`}>Create an account</Link>
        </p>
      </Card>
    </div>
  );
}
