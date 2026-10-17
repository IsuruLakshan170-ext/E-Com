import { Link } from 'react-router-dom';
import { useDocumentTitle } from '../hooks/misc';

export function NotFoundPage() {
  useDocumentTitle('Not found');
  return (
    <div className="error-page">
      <h1>Page not found</h1>
      <p>The page you are looking for does not exist or has moved.</p>
      <Link className="btn btn-primary" to="/">
        Back to the shop
      </Link>
    </div>
  );
}

export function ForbiddenPage() {
  useDocumentTitle('Not allowed');
  return (
    <div className="error-page">
      <h1>Not available for your role</h1>
      <p>Your account does not have access to this page. Ask an administrator if you need it.</p>
      <Link className="btn btn-secondary" to="/">
        Back to the shop
      </Link>
    </div>
  );
}
