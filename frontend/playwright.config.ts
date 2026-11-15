import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end suite. It runs against the application as a judge would see it: the Docker Compose
 * stack with the seeded demonstration factory (`docker compose up --build`), not against mocks.
 * Override the address with E2E_BASE_URL.
 */
export default defineConfig({
  testDir: './e2e',
  // The journey creates scenarios and runs simulations; one worker keeps runs from queuing behind each other.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
