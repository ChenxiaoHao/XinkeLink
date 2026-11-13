import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: {
    baseURL: process.env.ADMIN_E2E_BASE_URL || 'https://localhost:5173',
    ignoreHTTPSErrors: true,
    channel: 'chrome',
    viewport: { width: 1280, height: 800 },
  },
  webServer: {
    command: 'npm run dev',
    url: process.env.ADMIN_E2E_BASE_URL || 'https://localhost:5173',
    ignoreHTTPSErrors: true,
    reuseExistingServer: true,
    timeout: 30000,
  },
})
