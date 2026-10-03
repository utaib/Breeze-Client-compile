import { defineConfig } from '@playwright/test'
import { existsSync } from 'node:fs'

// Browser tests run the production build (vite preview), never the dev server,
// so what is tested is what ships in the jar.
//
// Two engines: Playwright's current Chromium, and Chrome for Testing 116, the
// same Chromium milestone MCEF 2.1.6 embeds (CEF 116.0.27, Chromium
// 116.0.5845.190). The 116 project runs when CHROME116 points at a binary, or
// when the default install path exists.
const chrome116 = process.env.CHROME116 ?? '/opt/chrome116/chrome-linux64/chrome'

export default defineConfig({
  testDir: 'tests/e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: true,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:4173',
    viewport: { width: 1280, height: 720 },
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run preview',
    url: 'http://localhost:4173',
    reuseExistingServer: true,
    timeout: 30_000,
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
    ...(existsSync(chrome116)
      ? [{ name: 'chrome116', use: { browserName: 'chromium' as const, launchOptions: { executablePath: chrome116 } } }]
      : []),
  ],
})
