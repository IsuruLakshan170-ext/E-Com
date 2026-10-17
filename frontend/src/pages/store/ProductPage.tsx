import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { catalogApi } from '../../api/endpoints';
import { errorMessage } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { useCart } from '../../cart/CartContext';
import { ProductImage } from '../../components/domain/ProductImage';
import { ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { Field } from '../../components/ui/Field';
import { MoneyText } from '../../components/ui/Money';
import { PageLoading } from '../../components/ui/Spinner';
import { useToasts } from '../../components/ui/Toasts';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';
import { SCOPES } from '../../lib/scopes';

const MAX_PER_LINE = 100;

export function ProductPage() {
  const { id = '' } = useParams();
  const product = useAsync((signal) => catalogApi.getProduct(id, false, signal), [id]);
  const { status, hasScope } = useAuth();
  const { add } = useCart();
  const toasts = useToasts();
  const navigate = useNavigate();
  const location = useLocation();
  const [quantity, setQuantity] = useState(1);
  const [adding, setAdding] = useState(false);
  useDocumentTitle(product.data?.name ?? 'Product');

  if (product.loading && !product.data) return <PageLoading />;
  if (!product.data) return <ProblemAlert error={product.error} title="Product not available" />;
  const p = product.data;
  const maxQuantity = Math.max(1, Math.min(MAX_PER_LINE, p.availableQuantity));

  const onAdd = async () => {
    if (status !== 'authenticated') {
      navigate(`/login?next=${encodeURIComponent(location.pathname)}`);
      return;
    }
    setAdding(true);
    try {
      await add(p.id, quantity);
      toasts.success(`${quantity} × ${p.name} added to your cart`);
    } catch (error) {
      toasts.error(errorMessage(error));
    } finally {
      setAdding(false);
    }
  };

  const canBuy = status !== 'authenticated' || hasScope(SCOPES.CART_WRITE);

  return (
    <div className="page product-page">
      <p className="breadcrumbs">
        <Link to="/">Shop</Link> / {p.name}
      </p>
      <div className="product-detail">
        <ProductImage product={p} className="product-hero" />
        <div>
          <h1>{p.name}</h1>
          <p className="muted">SKU {p.sku}</p>
          <p className="product-price-lg">
            <MoneyText value={p.price} />
            <span className="muted small"> excl. tax</span>
          </p>
          <p className={p.inStock ? 'stock stock-in' : 'stock stock-out'}>
            {p.inStock ? `${p.availableQuantity} available` : 'Out of stock'}
          </p>
          {p.description && <p className="product-description">{p.description}</p>}
          {canBuy && p.inStock && (
            <div className="buy-box">
              <Field label="Quantity">
                <input
                  type="number"
                  min={1}
                  max={maxQuantity}
                  value={quantity}
                  onChange={(e) => setQuantity(Math.max(1, Math.min(maxQuantity, Number(e.target.value) || 1)))}
                />
              </Field>
              <Button variant="primary" busy={adding} onClick={onAdd}>
                {status === 'authenticated' ? 'Add to cart' : 'Sign in to buy'}
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
