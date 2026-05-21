import { defineConfig } from "vite";

// Relative base so the bundle works when loaded from the WebView's asset loader.
export default defineConfig({
  base: "./",
  build: {
    outDir: "dist",
    target: "es2022",
  },
});
