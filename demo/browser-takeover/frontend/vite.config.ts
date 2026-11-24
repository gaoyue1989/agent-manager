import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const target = process.env.AGENT_ENTRY ?? 'http://127.0.0.1:30080';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
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
