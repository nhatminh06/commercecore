import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',
  use: { baseURL: 'http://127.0.0.1:4173', browserName: 'chromium', channel: 'chrome' },
  webServer: {
    command: 'npm run preview', url: 'http://127.0.0.1:4173',
    reuseExistingServer: false, timeout: 60000,
    env: { WRANGLER_SEND_METRICS: 'false', WRANGLER_LOG_PATH: '.wrangler/logs/wrangler.log' },
  },
});
