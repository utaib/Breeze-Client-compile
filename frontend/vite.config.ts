import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// One bundle, two runtimes: a desktop browser for development and tests, and
// the Chromium that MCEF embeds in Minecraft. Every choice below is about the
// second one, because it is the one that is hard to debug.
//
// - target chrome116: MCEF 2.1.6 ships CEF 116.0.27 (Chromium 116.0.5845.190,
//   from its java-cef commit a78e832). Anything newer than that engine must be
//   lowered at build time or it silently fails in game.
// - base './': the page is served from https://breeze.local/ inside the game
//   and from localhost in tests, so no absolute paths.
// - pinned, lowercase file names: the mod serves these from its own jar, and
//   MCEF's fallback mod:// scheme lowercases every path it looks up. A hashed,
//   mixed-case name would 404 there with no visible error.
// - fonts inlined: MCEF's MIME table has no woff2 entry. Inlining sidesteps the
//   question entirely and keeps the interface fully offline.
const lower = (s: string) => s.toLowerCase()

// The page never talks to the network itself: every request goes through the
// bridge, and Java decides what leaves the machine. MCEF starts Chromium with
// --disable-web-security, so this policy is what still stops a script from
// reaching out. Added to production builds only, because the dev server relies
// on an inline React refresh script and a websocket.
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self' data:",
  "connect-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-src 'none'",
].join('; ')

const productionCsp = {
  name: 'breeze-csp',
  apply: 'build' as const,
  transformIndexHtml: (html: string) =>
    html.replace('<meta charset="UTF-8" />', `<meta charset="UTF-8" />\n    <meta http-equiv="Content-Security-Policy" content="${CSP}" />`),
}

export default defineConfig({
  plugins: [react(), productionCsp],
  base: './',
  build: {
    target: 'chrome116',
    cssTarget: 'chrome116',
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true,
    assetsInlineLimit: (file) => /\.(woff2?|ttf)$/.test(file) ? true : undefined,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/breeze.js',
        chunkFileNames: (chunk) => `assets/${lower(chunk.name)}.js`,
        assetFileNames: (info) => {
          const name = lower(info.names?.[0] ?? info.name ?? 'asset')
          if (name.endsWith('.css')) return 'assets/breeze.css'
          return `assets/${name}`
        },
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
})
