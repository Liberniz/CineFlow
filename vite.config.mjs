import { defineConfig } from 'vite';

// The Android TV build runs inside TV WebViews that can be far older than
// Electron's Chromium (Sony Bravia sets in particular ship years-old
// WebViews that cannot even parse ES2022 syntax, which leaves the app
// frozen on the startup splash). CINEFLOW_TV=1 lowers the transpile
// target for the TV bundle only; desktop builds keep es2022.
// Runtime APIs newer than the syntax level (flat/flatMap/replaceAll)
// are polyfilled by the Android bridge shim, which runs first.
const isTvBuild = process.env.CINEFLOW_TV === '1';
const jsTarget = isTvBuild ? 'es2017' : 'es2022';

// TV only: Vite wraps lazy chunk loads in __vitePreload(..., import.meta.url).
// import.meta is a parse error below Chrome 64, which would kill the whole
// bundle on the oldest TV WebViews. The lazy chunks (playback libraries the
// TV never uses; playback is native) have no preload deps, so the base URL
// is never actually consulted — document.baseURI is a safe stand-in.
const stripImportMetaUrl = {
  name: 'cineflow-strip-import-meta-url',
  generateBundle(_options, bundle) {
    for (const item of Object.values(bundle)) {
      if (item.type === 'chunk' && item.code.includes('import.meta.url')) {
        item.code = item.code.split('import.meta.url').join('document.baseURI');
      }
    }
  }
};

export default defineConfig({
  plugins: isTvBuild ? [stripImportMetaUrl] : [],
  root: '.',
  base: './',
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    target: jsTarget,
    cssTarget: jsTarget,
    // TV only: no <link rel=modulepreload> hints; the oldest WebViews
    // gain nothing from them. (import.meta.url is stripped separately
    // by the plugin above.)
    modulePreload: isTvBuild ? false : true,
    minify: 'esbuild',
    cssMinify: true,
    reportCompressedSize: false
  }
});
