import { defineConfig, devices } from '@playwright/test';

/**
 * The M3 gate's browser: Chromium only (spec #28), driving the *built* bundle —
 * the webServer is `vite preview`, the analogue of the gates booting the real
 * jar instead of a dev server. It serves what `nx build web` last produced
 * (the gate builds right before this) and proxies /api to the control plane
 * the gate booted on 8085.
 *
 * vite runs directly, not via `nx preview web`: a continuous nx target hands
 * the process to the nx daemon, so Playwright's shutdown would leave vite
 * squatting 4200 for every later run.
 */
export default defineConfig({
  testDir: './src',
  outputDir: './test-results',
  // The scenario contains a real engine run polled to terminal (240s budget,
  // M1 notes) on top of the build-deploy choreography — one generous ceiling.
  timeout: 600_000,
  expect: { timeout: 15_000 },
  // One spec, one world: parallelism could only interleave lifecycles.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4200',
    trace: 'retain-on-failure',
    // A single stuck click/drag should fail in seconds, not eat the whole
    // scenario budget; the long waits are explicit expect timeouts instead.
    actionTimeout: 15_000,
  },
  webServer: {
    command: 'npx vite preview',
    cwd: '../web',
    url: 'http://localhost:4200',
    // A squatter on 4200 could serve a stale bundle — refuse it, loudly
    // (the 8085 squatter refusal's sibling).
    reuseExistingServer: false,
    timeout: 180_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
