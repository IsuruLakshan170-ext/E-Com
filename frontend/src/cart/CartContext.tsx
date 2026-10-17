import { type ReactNode, createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { cartApi } from '../api/endpoints';
import type { Cart, UUID } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { SCOPES } from '../lib/scopes';

interface CartContextValue {
  /** Only customers (cart scopes) have a cart; staff-only roles browse without one. */
  enabled: boolean;
  cart: Cart | null;
  /** Why the last load failed (null once it succeeds); the cart pages show it with a retry. */
  loadError: unknown;
  count: number;
  refresh: () => Promise<void>;
  add: (productId: UUID, quantity: number) => Promise<void>;
  setQuantity: (productId: UUID, quantity: number) => Promise<void>;
  remove: (productId: UUID) => Promise<void>;
  clear: () => Promise<void>;
}

const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const { status, hasScope } = useAuth();
  const enabled = status === 'authenticated' && hasScope(SCOPES.CART_READ);
  const [cart, setCart] = useState<Cart | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);

  const refresh = useCallback(async () => {
    if (!enabled) {
      setCart(null);
      setLoadError(null);
      return;
    }
    try {
      setCart(await cartApi.get());
      setLoadError(null);
    } catch (error) {
      setLoadError(error);
      throw error;
    }
  }, [enabled]);

  useEffect(() => {
    refresh().catch(() => undefined); // kept in loadError for the cart pages
  }, [refresh]);

  const add = useCallback(async (productId: UUID, quantity: number) => {
    setCart(await cartApi.addItem({ productId, quantity }));
  }, []);
  const setQuantity = useCallback(async (productId: UUID, quantity: number) => {
    setCart(await cartApi.setQuantity(productId, quantity));
  }, []);
  const remove = useCallback(async (productId: UUID) => {
    setCart(await cartApi.removeItem(productId));
  }, []);
  const clear = useCallback(async () => {
    await cartApi.clear();
    setCart(await cartApi.get());
  }, []);

  const value = useMemo<CartContextValue>(
    () => ({ enabled, cart, loadError, count: cart?.totalQuantity ?? 0, refresh, add, setQuantity, remove, clear }),
    [enabled, cart, loadError, refresh, add, setQuantity, remove, clear],
  );
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const context = useContext(CartContext);
  if (!context) {
    throw new Error('useCart must be used inside <CartProvider>');
  }
  return context;
}
