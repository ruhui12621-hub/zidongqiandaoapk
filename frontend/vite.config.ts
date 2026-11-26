import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

export default defineConfig({
  plugins: [react()],
  base: "./",
  root: resolve(__dirname),
  build: {
    outDir: resolve(__dirname, "../app/src/main/assets/ui"),
    emptyOutDir: true,
    assetsDir: "static",
    sourcemap: false,
    minify: "esbuild"
  }
});
