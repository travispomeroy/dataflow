import { defaultClientConditions } from 'vite';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The bundle fetches relative /api/... only — dev and preview proxy to the
// control plane, so no CORS and no base-URL env ever enter the bundle
// (control-plane-served static UI is an M8 productionization note).
const apiProxy = { '/api': 'http://localhost:8085' };

export default defineConfig(() => ({
  root: import.meta.dirname,
  cacheDir: '../../node_modules/.vite/apps/web',
  server:{
    port: 4200,
    host: 'localhost',
    proxy: apiProxy,
  },
  preview:{
    port: 4200,
    host: 'localhost',
    proxy: apiProxy,
  },
  plugins: [react()],
  // Workspace libs export their TS source under the "ui" condition (see
  // tsconfig.base.json customConditions) — without it Vite falls back to the
  // "default" export, a type-declaration file it cannot bundle.
  resolve: {
    conditions: ['ui', ...defaultClientConditions],
  },
  build: {
    outDir: './dist',
    emptyOutDir: true,
    reportCompressedSize: true,
    commonjsOptions: {
      transformMixedEsModules: true,
    },
  },
  test: {
    watch: false,
    environment: 'node',
    include: ['src/**/*.spec.ts', 'src/**/*.spec.tsx'],
  },
}));
