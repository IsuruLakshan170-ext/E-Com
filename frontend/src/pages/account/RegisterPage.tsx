import { type FormEvent, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { fieldErrors } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { Field, FormActions } from '../../components/ui/Field';
import { Card } from '../../components/ui/Layout';
import { useDocumentTitle } from '../../hooks/misc';
import { passwordProblem, safeNextPath } from '../../lib/validation';

export function RegisterPage() {
  useDocumentTitle('Create account');
  const { status, register } = useAuth();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const next = safeNextPath(params.get('next'));
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  if (status === 'authenticated') {
    return <Navigate to={next} replace />;
  }

  const passwordIssue = passwordProblem(password, email);
  const confirmIssue = confirmation !== password ? 'The passwords do not match.' : null;
  const serverErrors = fieldErrors(error);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (passwordIssue || confirmIssue || !fullName.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await register(email, password, fullName);
      navigate(next, { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page page-narrow">
      <Card title="Create your account">
        <form onSubmit={onSubmit} noValidate>
          <Field label="Full name" required error={(touched && !fullName.trim() ? 'Required' : null) ?? serverErrors.fullName}>
            <input autoComplete="name" value={fullName} maxLength={100} onChange={(e) => setFullName(e.target.value)} />
          </Field>
          <Field label="Email" required error={serverErrors.email}>
            <input type="email" autoComplete="email" value={email} maxLength={254} onChange={(e) => setEmail(e.target.value)} />
          </Field>
          <Field
            label="Password"
            required
            hint="At least 12 characters. A short sentence is easy to remember and hard to guess."
            error={(touched ? passwordIssue : null) ?? serverErrors.password}
          >
            <input type="password" autoComplete="new-password" value={password} maxLength={72} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <Field label="Repeat password" required error={touched ? confirmIssue : null}>
            <input type="password" autoComplete="new-password" value={confirmation} maxLength={72} onChange={(e) => setConfirmation(e.target.value)} />
          </Field>
          <ProblemAlert error={error} title="Could not create the account" />
          <FormActions>
            <Button type="submit" variant="primary" busy={busy}>
              Create account
            </Button>
          </FormActions>
        </form>
        <p className="muted">
          Already registered? <Link to={`/login?next=${encodeURIComponent(next)}`}>Sign in</Link>
        </p>
      </Card>
    </div>
  );
}
