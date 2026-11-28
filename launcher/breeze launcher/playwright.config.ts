import { defineConfig } from "@playwright/test";

/**
 * Interface tests against the browser preview (preview.html, development
 * only): the real React app with Tauri's IPC mocked and every network answer
 * stubbed in tests/ui/fixtures.ts: the Mods page on a sample mods folder.
 */
export default defineConfig({
  testDir: "tests/ui",
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://127.0.0.1:1430",
    viewport: { width: 1180, height: 760 },
    // Software WebGL so the 3D player is really drawn: newer Chromium needs
    // ANGLE with SwiftShader, older builds the --use-gl switch.
    launchOptions: { args: ["--use-gl=swiftshader", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--enable-webgl", "--ignore-gpu-blocklist"] },
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npx vite --host 127.0.0.1 --port 1430 --strictPort",
    url: "http://127.0.0.1:1430/preview.html",
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
