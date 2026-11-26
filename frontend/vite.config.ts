import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const frontendRoot = fileURLToPath(new URL(".", import.meta.url));

export default defineConfig({
  plugins: [react()],
  base: "./",
  root: frontendRoot,
  build: {
    outDir: resolve(frontendRoot, "../app/src/main/assets/ui"),
    emptyOutDir: true,
    assetsDir: "static",
    sourcemap: false,
    minify: "esbuild",
    rollupOptions: {
      input: {
        main: resolve(frontendRoot, "index.html"),
        "skland-status": resolve(frontendRoot, "skland-status.html")
      }
    }
  }
});
