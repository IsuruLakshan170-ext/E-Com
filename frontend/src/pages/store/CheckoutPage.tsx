import { type FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { cartApi, ordersApi } from '../../api/endpoints';
import { ApiError } from '../../api/errors';
import { newIdempotencyKey } from '../../api/idempotency';
import type { CartQuote } from '../../api/types';
import { useCart } from '../../cart/CartContext';
import { CartPending } from '../../cart/CartPending';
import { AddressForm, type AddressDraft, EMPTY_ADDRESS, addressProblems, toShippingAddress } from '../../components/domain/AddressForm';
import { Totals } from '../../components/domain/OrderSummary';
import { Alert, ProblemAlert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { FormActions } from '../../components/ui/Field';
import { Card, PageHeader } from '../../components/ui/Layout';
import { MoneyText } from '../../components/ui/Money';
import { useDocumentTitle } from '../../hooks/misc';
import { formatMinor } from '../../lib/format';

type Step = 'address' | 'review';

/**
 * Quote -> confirm -> place (backend M1/M5):
 * - the total the person confirms is sent as expectedTotalMinor; if prices changed the backend answers
 *   PRICE_CHANGED and we show the new total for a fresh confirmation (with a new idempotency key);
 * - one idempotency key per confirmation: a retry after a network error reuses it, so it can never create two orders.
 */
export function CheckoutPage() {
  useDocumentTitle('Checkout');
  const { cart, refresh } = useCart();
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('address');
  const [address, setAddress] = useState<AddressDraft>(EMPTY_ADDRESS);
  const [showProblems, setShowProblems] = useState(false);
  const [quote, setQuote] = useState<CartQuote | null>(null);
  const [orderKey, setOrderKey] = useState<string>('');
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!cart) return <CartPending title="Checkout" />;
  if (cart.items.length === 0) {
    return (
      <div className="page">
        <PageHeader title="Checkout" />
        <Alert tone="info" title="Your cart is empty">
          <Link to="/">Continue shopping</Link>
        </Alert>
      </div>
    );
  }

  const problems = addressProblems(address);

  const requestQuote = async (message: string | null) => {
    const fresh = await cartApi.quote(address.countryCode);
    setQuote(fresh);
    setOrderKey(newIdempotencyKey()); // new confirmation => new key
    setNotice(message);
    setStep('review');
  };

  const onReview = async (event: FormEvent) => {
    event.preventDefault();
    setShowProblems(true);
    if (Object.keys(problems).length > 0) return;
    setBusy(true);
    setError(null);
    try {
      await requestQuote(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const onPlaceOrder = async () => {
    if (!quote) return;
    setBusy(true);
    setError(null);
    try {
      const result = await ordersApi.placeOrder(
        { shippingAddress: toShippingAddress(address), expectedTotalMinor: quote.total.amountMinor },
        orderKey,
      );
      await refresh().catch(() => undefined);
      navigate(`/account/orders/${result.data.id}`, { replace: true, state: { placed: true } });
    } catch (e) {
      if (e instanceof ApiError && e.code === 'PRICE_CHANGED') {
        const actual = typeof e.details.actualTotalMinor === 'number' ? formatMinor(e.details.actualTotalMinor, quote.total.currency) : null;
        await requestQuote(`Prices changed since your quote${actual ? `: the total is now ${actual}` : ''}. Please check and confirm again.`)
          .catch((quoteError: unknown) => setError(quoteError));
      } else if (e instanceof ApiError && e.is('INSUFFICIENT_STOCK', 'PRODUCT_UNAVAILABLE', 'CART_EMPTY')) {
        await refresh().catch(() => undefined);
        setError(e);
      } else {
        setError(e); // network errors etc.: "Place order" again retries with the SAME key
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader title="Checkout" subtitle={step === 'address' ? 'Step 1 of 2: delivery' : 'Step 2 of 2: review and confirm'} />
      <div className="checkout-layout">
        <div>
          {step === 'address' && (
            <form onSubmit={onReview} noValidate>
              <AddressForm value={address} onChange={setAddress} problems={showProblems ? problems : {}} disabled={busy} />
              <ProblemAlert error={error} title="Could not calculate your total" />
              <FormActions>
                <Link to="/cart" className="btn btn-ghost">
                  Back to cart
                </Link>
                <Button type="submit" variant="primary" busy={busy}>
                  Review order
                </Button>
              </FormActions>
            </form>
          )}
          {step === 'review' && quote && (
            <Card title="Review your order">
              {notice && <Alert tone="warning">{notice}</Alert>}
              <ul className="review-lines">
                {cart.items.map((line) => (
                  <li key={line.productId}>
                    <span>
                      {line.quantity} × {line.name}
                    </span>
                    <MoneyText value={line.lineTotal} />
                  </li>
                ))}
              </ul>
              <Totals subtotal={quote.subtotal} taxRateBasisPoints={quote.taxRateBasisPoints} tax={quote.tax} total={quote.total} />
              <p className="muted small">
                Delivering to {address.recipientName}, {address.city} ({address.countryCode}). Your items are reserved once
                you place the order; unpaid orders are released after a short time.
              </p>
              {error !== null && (
                <ProblemAlert
                  error={error}
                  title={isApiCode(error, 'LIMIT_EXCEEDED') ? 'You have too many unpaid orders' : 'Your order was not placed'}
                />
              )}
              {isApiCode(error, 'LIMIT_EXCEEDED') && (
                <p>
                  <Link to="/account/orders">Pay or cancel an open order</Link> first.
                </p>
              )}
              <FormActions>
                <Button variant="ghost" disabled={busy} onClick={() => setStep('address')}>
                  Change address
                </Button>
                <Button variant="primary" busy={busy} disabled={!quote.readyForCheckout} onClick={onPlaceOrder}>
                  Place order · {formatMinor(quote.total.amountMinor, quote.total.currency)}
                </Button>
              </FormActions>
              {!quote.readyForCheckout && <p className="field-error">Some items are no longer available. Go back to your cart.</p>}
            </Card>
          )}
        </div>
        <Card title="In your cart" className="checkout-aside">
          <p className="summary-row">
            <span>{cart.totalQuantity} item(s)</span>
            <MoneyText value={cart.subtotal} />
          </p>
          <p className="muted small">Before tax</p>
        </Card>
      </div>
    </div>
  );
}

function isApiCode(error: unknown, code: string): boolean {
  return error instanceof ApiError && error.code === code;
}
