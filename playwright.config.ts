import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  use: {
    baseURL: 'http://127.0.0.1:8080',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'bash scripts/start-e2e-stack.sh',
    url: 'http://127.0.0.1:8080/',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
