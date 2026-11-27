import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', '.wrangler/**', 'test-results/**'] },
  { files: ['**/*.{ts,tsx}'], extends: [js.configs.recommended, ...tseslint.configs.recommended] },
);
