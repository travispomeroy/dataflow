import { defineConfig } from 'vitest/config';

export default defineConfig({
	root: import.meta.dirname,
	cacheDir: '../../node_modules/.vite/libs/dataflow-config',
	test: {
		watch: false,
		environment: 'node',
		include: ['src/**/*.spec.ts'],
	},
});
