import { defineConfig } from "vite";

// Relative base so the bundle works when loaded from the WebView's asset loader.
export default defineConfig({
  base: "./",
  build: {
    outDir: "dist",
    target: "es2022",
  },
  // Dev-only: proxy /v1 to a local OpenAI-compatible server so the browser talks
  // same-origin (no CORS). Defaults to a local Ollama instance. Override the
  // target with VITE_LLM_PROXY_TARGET. This block has no effect on `vite build`.
  server: {
    proxy: {
      "/v1": {
        target: process.env.VITE_LLM_PROXY_TARGET || "http://127.0.0.1:11434",
        changeOrigin: true,
      },
    },
  },
});
