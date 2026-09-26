import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';

export default defineConfig({
  testDir: './src',
  testMatch: 'faith.spec.ts',
  workers: 1,
  outputDir: '../../dist/faith-e2e',
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4305',
    browserName: 'chromium',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node scripts/serve-faith-e2e.mjs',
    cwd: resolve(__dirname, '../..'),
    url: 'http://127.0.0.1:4305/learn',
    reuseExistingServer: false,
  },
});
