import { defineConfig } from "@playwright/test";

import baseConfig from "./playwright.config";

// Windows reserves dynamic port ranges on some developer machines; keep the
// production acceptance server outside the commonly excluded 3081-3180 block.
const port = 3201;

export default defineConfig({
  ...baseConfig,
  use: {
    ...baseConfig.use,
    baseURL: `http://127.0.0.1:${port}`,
  },
  webServer: {
    command: "node scripts/start-acceptance-server.mjs",
    env: {
      HOSTNAME: "0.0.0.0",
      MENU_API_BASE_URL: "http://127.0.0.1:8080",
      PORT: String(port),
    },
    port,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
