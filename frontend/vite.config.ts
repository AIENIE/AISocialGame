import { defineConfig } from "vite";
import dyadComponentTagger from "@dyad-sh/react-vite-component-tagger";
import react from "@vitejs/plugin-react-swc";
import path from "path";

export default defineConfig(() => {
  const backendPort = process.env.VITE_LOCAL_BACKEND_PORT || "11031";
  const backendTarget = `http://localhost:${backendPort}`;
  const allowedHosts = ["localhost", "127.0.0.1", "aisocialgame.localhut.com", "aisocialgame.aienie.com"];

  return {
    server: {
      host: "::",
      port: 11030,
      strictPort: true,
      allowedHosts,
      proxy: {
        "/api": {
          target: backendTarget,
          changeOrigin: true,
        },
        "/ws": {
          target: backendTarget,
          changeOrigin: true,
          ws: true,
        },
      },
    },
    preview: {
      host: "::",
      port: 11030,
      strictPort: true,
      allowedHosts,
    },
    plugins: [dyadComponentTagger(), react()],
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "./src"),
      },
    },
  };
});
