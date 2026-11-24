import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const target = process.env.AGENT_ENTRY ?? 'http://127.0.0.1:30080';
const https = process.env.DEMO_HTTPS
  ? {
      key: readFileSync(resolve(process.cwd(), '.cert/key.pem')),
      cert: readFileSync(resolve(process.cwd(), '.cert/cert.pem')),
    }
  : undefined;

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
    port: Number(process.env.DEMO_PORT ?? 5188),
    https,
    proxy: {
      '/agent': {
        target,
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
