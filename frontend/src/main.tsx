import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { AuthProvider } from './auth/AuthContext';
import { CartProvider } from './cart/CartContext';
import { DialogProvider } from './components/ui/Dialogs';
import { ToastProvider } from './components/ui/Toasts';
import './styles/global.css';

const root = document.getElementById('root');
if (!root) {
  throw new Error('Missing #root element');
}

createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <DialogProvider>
          <AuthProvider>
            <CartProvider>
              <App />
            </CartProvider>
          </AuthProvider>
        </DialogProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
);
