import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { CartLine } from '../../api/types';
import { errorMessage } from '../../api/errors';
import { useCart } from '../../cart/CartContext';
import { CartPending } from '../../cart/CartPending';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { useDialogs } from '../../components/ui/Dialogs';
import { Card, EmptyState, PageHeader } from '../../components/ui/Layout';
import { MoneyText } from '../../components/ui/Money';
import { useToasts } from '../../components/ui/Toasts';
import { useDocumentTitle } from '../../hooks/misc';
import { humanize } from '../../lib/format';

export function CartPage() {
  useDocumentTitle('Your cart');
  const { cart, setQuantity, remove, clear } = useCart();
  const { confirm } = useDialogs();
  const toasts = useToasts();
  const navigate = useNavigate();
  const [busyLine, setBusyLine] = useState<string | null>(null);

  if (!cart) return <CartPending title="Your cart" />;

  const run = async (productId: string, work: () => Promise<void>) => {
    setBusyLine(productId);
    try {
      await work();
    } catch (error) {
      toasts.error(errorMessage(error));
    } finally {
      setBusyLine(null);
    }
  };

  const onClear = async () => {
    if (await confirm({ title: 'Empty your cart?', message: 'All items will be removed.', confirmLabel: 'Empty cart', danger: true })) {
      await run('all', clear);
    }
  };

  if (cart.items.length === 0) {
    return (
      <div className="page">
        <PageHeader title="Your cart" />
        <EmptyState title="Your cart is empty">
          <Link className="btn btn-primary" to="/">
            Browse products
          </Link>
        </EmptyState>
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader title="Your cart" actions={<Button variant="ghost" onClick={onClear}>Empty cart</Button>} />
      {!cart.readyForCheckout && (
        <Alert tone="warning" title="Some items need attention">
          Adjust or remove the highlighted items before checking out.
        </Alert>
      )}
      <div className="cart-layout">
        <ul className="cart-lines">
          {cart.items.map((line) => (
            <CartLineRow
              key={line.productId}
              line={line}
              busy={busyLine === line.productId}
              onQuantity={(q) => run(line.productId, () => setQuantity(line.productId, q))}
              onRemove={() => run(line.productId, () => remove(line.productId))}
            />
          ))}
        </ul>
        <Card title="Summary" className="cart-summary">
          <p className="summary-row">
            <span>{cart.totalQuantity} item(s)</span>
            <MoneyText value={cart.subtotal} strong />
          </p>
          <p className="muted small">Tax is calculated for your delivery country in the next step.</p>
          <Button variant="primary" disabled={!cart.readyForCheckout} onClick={() => navigate('/checkout')}>
            Checkout
          </Button>
        </Card>
      </div>
    </div>
  );
}

function CartLineRow({ line, busy, onQuantity, onRemove }: { line: CartLine; busy: boolean; onQuantity: (q: number) => void; onRemove: () => void }) {
  const [quantity, setQuantity] = useState(line.quantity);
  const max = Math.min(100, Math.max(1, line.availableQuantity ?? 100));
  return (
    <li className={line.issue ? 'cart-line cart-line-issue' : 'cart-line'}>
      <div className="cart-line-info">
        <Link to={`/products/${line.productId}`}>{line.name ?? 'Unavailable product'}</Link>
        {line.sku && <span className="muted small"> {line.sku}</span>}
        {line.issue && <p className="field-error">{humanize(line.issue)}{line.availableQuantity !== null ? ` - ${line.availableQuantity} available` : ''}</p>}
      </div>
      <div className="cart-line-qty">
        <input
          type="number"
          min={1}
          max={max}
          value={quantity}
          aria-label={`Quantity for ${line.name ?? 'item'}`}
          disabled={busy}
          onChange={(e) => setQuantity(Math.max(1, Math.min(max, Number(e.target.value) || 1)))}
        />
        {quantity !== line.quantity && (
          <Button size="sm" busy={busy} onClick={() => onQuantity(quantity)}>
            Update
          </Button>
        )}
      </div>
      <div className="cart-line-total">
        <MoneyText value={line.lineTotal} />
      </div>
      <Button size="sm" variant="ghost" disabled={busy} onClick={onRemove} aria-label={`Remove ${line.name ?? 'item'}`}>
        Remove
      </Button>
    </li>
  );
}
