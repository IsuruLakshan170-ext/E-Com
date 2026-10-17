import { type FormEvent, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { catalogApi } from '../../api/endpoints';
import type { Product, ProductSort } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { useCart } from '../../cart/CartContext';
import { ProductImage } from '../../components/domain/ProductImage';
import { ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { EmptyState, PageHeader } from '../../components/ui/Layout';
import { MoneyText } from '../../components/ui/Money';
import { Pagination } from '../../components/ui/Pagination';
import { PageLoading } from '../../components/ui/Spinner';
import { useToasts } from '../../components/ui/Toasts';
import { errorMessage } from '../../api/errors';
import { useAsync } from '../../hooks/useAsync';
import { useDocumentTitle } from '../../hooks/misc';
import { SCOPES } from '../../lib/scopes';

const SORTS: { value: ProductSort; label: string }[] = [
  { value: 'NEWEST', label: 'Newest' },
  { value: 'NAME_ASC', label: 'Name A-Z' },
  { value: 'PRICE_ASC', label: 'Price: low to high' },
  { value: 'PRICE_DESC', label: 'Price: high to low' },
];

export function CatalogPage() {
  useDocumentTitle('Shop');
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const categoryId = params.get('category') ?? '';
  const sort = (params.get('sort') as ProductSort | null) ?? 'NEWEST';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);
  const [text, setText] = useState(q);
  useEffect(() => setText(q), [q]);

  const categories = useAsync((signal) => catalogApi.listCategories(signal), []);
  const products = useAsync(
    (signal) => catalogApi.searchProducts({ q: q || undefined, categoryId: categoryId || undefined, sort, page, size: 24 }, signal),
    [q, categoryId, sort, page],
  );

  const update = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };

  const onSearch = (event: FormEvent) => {
    event.preventDefault();
    update({ q: text.trim() });
  };

  return (
    <div className="page">
      <PageHeader title="Shop" subtitle="Prices exclude tax. Tax for your country is shown before you pay." />
      <form className="toolbar" onSubmit={onSearch} role="search">
        <input type="search" value={text} placeholder="Search by name or SKU" aria-label="Search" maxLength={100} onChange={(e) => setText(e.target.value)} />
        <select aria-label="Category" value={categoryId} onChange={(e) => update({ category: e.target.value })}>
          <option value="">All categories</option>
          {(categories.data ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        <select aria-label="Sort" value={sort} onChange={(e) => update({ sort: e.target.value })}>
          {SORTS.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
        <Button type="submit" variant="primary">
          Search
        </Button>
      </form>

      <ProblemAlert error={products.error} title="Could not load products" />
      {products.loading && !products.data && <PageLoading />}
      {products.data && products.data.items.length === 0 && (
        <EmptyState title="No products found">{q ? 'Try a different search.' : 'Nothing is for sale yet.'}</EmptyState>
      )}
      {products.data && products.data.items.length > 0 && (
        <>
          <div className="product-grid">
            {products.data.items.map((p) => (
              <ProductCard key={p.id} product={p} />
            ))}
          </div>
          <Pagination
            page={products.data.page}
            totalPages={products.data.totalPages}
            totalItems={products.data.totalItems}
            onChange={(next) => update({ page: String(next) })}
          />
        </>
      )}
    </div>
  );
}

function ProductCard({ product }: { product: Product }) {
  const { status, hasScope } = useAuth();
  const { add } = useCart();
  const toasts = useToasts();
  const [adding, setAdding] = useState(false);
  const canBuy = status === 'authenticated' && hasScope(SCOPES.CART_WRITE);

  const onAdd = async () => {
    setAdding(true);
    try {
      await add(product.id, 1);
      toasts.success(`${product.name} added to your cart`);
    } catch (error) {
      toasts.error(errorMessage(error));
    } finally {
      setAdding(false);
    }
  };

  return (
    <article className="product-card">
      <Link to={`/products/${product.id}`} className="product-card-link">
        <ProductImage product={product} className="product-thumb" />
        <h2 className="product-name">{product.name}</h2>
      </Link>
      <p className="product-price">
        <MoneyText value={product.price} />
      </p>
      <p className={product.inStock ? 'stock stock-in' : 'stock stock-out'}>
        {product.inStock ? `${product.availableQuantity} in stock` : 'Out of stock'}
      </p>
      {canBuy && (
        <Button size="sm" variant="primary" busy={adding} disabled={!product.inStock} onClick={onAdd}>
          Add to cart
        </Button>
      )}
    </article>
  );
}
