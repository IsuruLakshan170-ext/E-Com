import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Dev: the browser only talks to this server (port 3000, which the backend's CORS list already allows);
// /api is proxied to the Spring Boot backend, so requests are same-origin and need no CORS at all.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    strictPort: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  preview: { port: 3000, strictPort: true },
  build: { sourcemap: false, target: 'es2022' },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
  },
});
