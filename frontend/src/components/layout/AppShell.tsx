import { type FormEvent, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { useCart } from '../../cart/CartContext';
import { SCOPES, isStaff } from '../../lib/scopes';

export function AppShell() {
  const { status, user, logout, hasScope, retry } = useAuth();
  const { enabled: cartEnabled, count } = useCart();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const staff = user ? isStaff(user.scopes) : false;

  const onSearch = (event: FormEvent) => {
    event.preventDefault();
    const q = search.trim();
    navigate(q ? `/?q=${encodeURIComponent(q)}` : '/');
  };

  const onLogout = async () => {
    await logout();
    navigate('/');
  };

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="site-header">
        <div className="site-header-inner">
          <Link to="/" className="brand" aria-label="E-Com home">
            <span className="brand-mark" aria-hidden="true">E</span>
            <span>E-Com</span>
          </Link>
          <form className="header-search" role="search" onSubmit={onSearch}>
            <input type="search" placeholder="Search products" aria-label="Search products" value={search} onChange={(e) => setSearch(e.target.value)} />
          </form>
          <nav className="header-nav" aria-label="Main">
            <NavLink to="/" end>
              Shop
            </NavLink>
            {staff && <NavLink to="/backoffice">Back office</NavLink>}
            {status === 'authenticated' && hasScope(SCOPES.ORDERS_READ) && <NavLink to="/account/orders">Orders</NavLink>}
            {cartEnabled && (
              <NavLink to="/cart" className="cart-link" aria-label={`Cart, ${count} item(s)`}>
                Cart{count > 0 && <span className="cart-count">{count}</span>}
              </NavLink>
            )}
            {status === 'authenticated' && user ? (
              <>
                <NavLink to="/account" title={user.profile.email}>
                  {user.profile.fullName.split(' ')[0] || 'Account'}
                </NavLink>
                <button type="button" className="link-button" onClick={onLogout}>
                  Sign out
                </button>
              </>
            ) : status === 'unavailable' ? (
              <button type="button" className="link-button" onClick={() => void retry()}>
                Reconnect
              </button>
            ) : (
              status === 'anonymous' && (
                <>
                  <NavLink to="/login">Sign in</NavLink>
                  <NavLink to="/register" className="btn btn-primary btn-sm">
                    Create account
                  </NavLink>
                </>
              )
            )}
          </nav>
        </div>
      </header>
      <main id="main" className="site-main">
        <Outlet />
      </main>
      <footer className="site-footer">
        <span>E-Com</span>
        <span className="muted">Prices exclude tax; tax is added at checkout for your country.</span>
      </footer>
    </div>
  );
}
