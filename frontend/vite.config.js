import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    // En desarrollo, /api se reenvía al backend Java local.
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080' },
    },
  },
  build: {
    // `pnpm build` deja la interfaz dentro del backend: un solo proceso sirve todo en :8080.
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
});
