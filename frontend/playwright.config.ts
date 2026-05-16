import { defineConfig } from "@playwright/test";

const hostResolverRules = process.env.PLAYWRIGHT_HOST_RESOLVER_RULES;

export default defineConfig({
  testDir: "./tests",
  retries: 0,
  workers: 1,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || "https://aisocialgame.seekerhut.com",
    headless: true,
    ignoreHTTPSErrors: process.env.PLAYWRIGHT_IGNORE_HTTPS_ERRORS !== "false",
    viewport: { width: 1440, height: 900 },
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH || "/usr/bin/chromium",
      args: [
        "--no-proxy-server",
        ...(hostResolverRules ? [`--host-resolver-rules=${hostResolverRules}`] : []),
      ],
    },
  },
});
