import { defineConfig, devices } from "@playwright/test";

const externalBaseUrl = process.env.PLAYWRIGHT_BASE_URL;
const port = 3000;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: "list",
  use: {
    baseURL: externalBaseUrl ?? `http://127.0.0.1:${port}`,
    extraHTTPHeaders: externalBaseUrl?.includes("ngrok")
      ? { "ngrok-skip-browser-warning": "1" }
      : undefined,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: externalBaseUrl
    ? undefined
    : {
        command: `pnpm dev --hostname 0.0.0.0 --port ${port}`,
        port,
        reuseExistingServer: true,
        timeout: 120_000,
      },
  projects: [
    {
      name: "desktop-chromium",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1440, height: 900 },
      },
    },
    {
      name: "tablet-chromium",
      use: {
        ...devices["Desktop Chrome"],
        hasTouch: true,
        viewport: { width: 820, height: 1180 },
      },
    },
    {
      name: "iphone-11-webkit",
      use: {
        ...devices["iPhone 11"],
      },
    },
    {
      name: "iphone-ios15-webkit",
      use: {
        ...devices["iPhone 11"],
        userAgent:
          "Mozilla/5.0 (iPhone; CPU iPhone OS 15_3_1 like Mac OS X) " +
          "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.3.1 Mobile/19D52 Safari/604.1",
      },
    },
    {
      name: "telegram-ios-webkit",
      use: {
        ...devices["iPhone 11"],
        userAgent:
          "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 " +
          "(KHTML, like Gecko) Mobile/15E148 Telegram-iOS/11.12",
        viewport: { width: 393, height: 852 },
      },
    },
    {
      name: "compact-ios-webkit",
      use: {
        ...devices["iPhone 11"],
        viewport: { width: 320, height: 700 },
      },
    },
    {
      name: "android-chromium",
      use: {
        ...devices["Pixel 5"],
      },
    },
  ],
});
