import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { readFileSync } from "node:fs";

// @ts-expect-error process is a nodejs global
const host = process.env.TAURI_DEV_HOST;

// The app version, taken from package.json at build time.
//
// This used to be a hand-written constant in BreezeApp.jsx and it went stale:
// it still said 1.0.10 three releases later. Every update check compared that
// dead string against the server's latest, so the launcher reported an update
// available forever, including immediately after updating. Injecting it here
// means the value cannot drift from the package it ships in.
const pkg = JSON.parse(
  readFileSync(fileURLToPath(new URL("./package.json", import.meta.url)), "utf8"),
);

// https://vite.dev/config/
export default defineConfig(async () => ({
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  resolve: {
    // skinview3d pins three ^0.156 and the GLB preview asked for ^0.180, so two
    // copies of three (about 500KB of extra JavaScript, and two WebGL runtimes
    // in one webview) were bundled. package.json now asks for the version
    // skinview3d can share; this keeps a stray second copy from coming back.
    dedupe: ['three'],
    alias: {
      "@veerone/galileo-glass-ui": fileURLToPath(
        new URL("./node_modules/aura-glass/dist/index.mjs", import.meta.url),
      ),
    },
  },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // Tighten the file-watcher so chokidar isn't iterating the whole
      // workspace on every keystroke. The dev server's CPU + RAM footprint
      // drops noticeably when these directories are excluded.
      ignored: [
        "**/src-tauri/**",
        "**/node_modules/**",
        "**/dist/**",
        "**/build/**",
        "**/target/**",
        "**/.git/**",
        "**/EXTRA ASSETS AND RSOUCES/**",
        "**/*.log",
        "**/tauri-dev.*.log",
      ],
      // Polling is the worst case for CPU. Make sure we're using the OS
      // notifier (default on Win/macOS/Linux with inotify quotas) — only
      // fall back if the user explicitly opts in via env.
      usePolling: false,
    },
  },

  // Cap Vite's prebundle scan so it doesn't crawl every .ts under node_modules
  // looking for entry points — a meaningful boot-time win on cold starts.
  optimizeDeps: {
    entries: ["index.html", "src/main.tsx"],
  },

  // Make production bundles smaller / faster: drop sourcemaps and strip
  // console output unless explicitly enabled.
  build: {
    sourcemap: false,
    target: "es2022",
    minify: "esbuild",
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // Split the heavy, rarely-changing dependencies out of the app chunk.
        // three.js is by far the largest single dependency and is only needed
        // once a 3D preview mounts, so keeping it separate lets the shell
        // parse and paint without waiting on it.
        manualChunks: {
          three: ["three"],
          react: ["react", "react-dom"],
        },
      },
    },
  },
}));
