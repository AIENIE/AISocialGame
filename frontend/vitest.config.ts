import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react-swc";
import path from "path";

// 独立于 vite.config.ts：仅用于单元测试，
// 避免把 frontend/tests 下的 Playwright e2e spec 当作单测收集。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  test: {
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    environment: "jsdom",
    pool: "threads",
    maxWorkers: 1,
    minWorkers: 1,
  },
});
