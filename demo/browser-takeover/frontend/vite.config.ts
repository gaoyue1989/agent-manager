import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const target = process.env.AGENT_ENTRY ?? 'http://127.0.0.1:30080';

export default defineConfig({
  plugins: [react()],
  optimizeDeps: {
    exclude: ['@novnc/novnc'],
  },
  build: {
    target: 'es2022',
  },
  server: {
    host: '0.0.0.0',
    port: 5188,
    proxy: {
      '/agent': {
        target,
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
