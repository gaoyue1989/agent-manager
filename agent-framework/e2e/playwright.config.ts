import { defineConfig, devices } from '@playwright/test';

// agent-framework E2E（e2e-ci-plan §6）：单运行器承载 API（node fetch 收 SSE）+ UI（chromium）
// project 分组映射 CI job：api-core / ui → e2e-core；api-multi / ui-multi → e2e-multi；api-sandbox → e2e-sandbox
const BASE = process.env.E2E_BASE ?? 'http://127.0.0.1:8100';

export default defineConfig({
  testDir: './tests',
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0, // CI 吸收刷新类时序 flake（UI 组）
  reporter: [['html', { open: 'never' }], ['line']],
  outputDir: '.runtime/test-results',
  use: {
    baseURL: BASE,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
  },
  projects: [
    { name: 'api-core', testMatch: /tests\/api-core\.spec\.ts/, use: { ...devicesDesktop() } },
    { name: 'api-multi', testMatch: /tests\/api-multi\.spec\.ts/, use: { ...devicesDesktop() } },
    { name: 'api-sandbox', testMatch: /tests\/api-sandbox\.spec\.ts/, use: { ...devicesDesktop() } },
    { name: 'ui', testMatch: /tests\/ui\.spec\.ts/, use: { ...devicesDesktop() } },
    { name: 'ui-multi', testMatch: /tests\/ui-multi\.spec\.ts/, use: { ...devicesDesktop() } },
    { name: 'api-multi-kill', testMatch: /tests\/api-multi-kill\.spec\.ts/, use: { ...devicesDesktop() } },
  ],
});

function devicesDesktop() {
  return { ...devices['Desktop Chrome'] };
}
