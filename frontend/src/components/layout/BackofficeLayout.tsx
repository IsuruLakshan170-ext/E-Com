import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { visibleNav } from '../../lib/scopes';

export function BackofficeLayout() {
  const { user } = useAuth();
  const sections = user ? visibleNav(user.scopes) : [];
  return (
    <div className="backoffice">
      <aside className="backoffice-nav" aria-label="Back office">
        <NavLink to="/backoffice" end className="backoffice-home">
          Dashboard
        </NavLink>
        {sections.map((section) => (
          <div key={section.title} className="nav-section">
            <span className="nav-section-title">{section.title}</span>
            {section.items.map((item) => (
              <NavLink key={item.to} to={item.to} end>
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}
        {user && (
          <p className="backoffice-role muted small">
            Signed in as {user.profile.email}
            <br />
            Role: {user.profile.role}
          </p>
        )}
      </aside>
      <div className="backoffice-content">
        <Outlet />
      </div>
    </div>
  );
}
