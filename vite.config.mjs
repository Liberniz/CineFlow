import { defineConfig } from 'vite';

// Two bundle flavours share this frontend:
//  - TV (default `vite build`): runs inside TV WebViews that can be far
//    older than Electron's Chromium. Sony Bravia sets in particular ship
//    years-old WebViews that cannot even parse ES2022 syntax, which left
//    the app frozen on the startup splash. Target is therefore ES2017,
//    and import.meta.url is stripped (see plugin below). Runtime APIs
//    newer than the syntax level (flat/flatMap/replaceAll) are
//    polyfilled by the Android bridge shim, which runs first.
//  - Desktop (`vite build --mode desktop`): Electron's modern Chromium,
//    keeps the ES2022 target.
// The TV flavour is the default because the Android CI workflow invokes
// plain `npm run build` verbatim; the desktop npm scripts opt up.
//
// Vite wraps lazy chunk loads in __vitePreload(..., import.meta.url), and
// import.meta is a parse error below Chrome 64 — enough to kill the whole
// bundle on the oldest TV WebViews. The lazy chunks (playback libraries
// the TV never uses; playback is native) have no preload deps, so the
// base URL is never actually consulted and document.baseURI is a safe
// stand-in.
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

export default defineConfig(({ mode }) => {
  const isDesktopBuild = mode === 'desktop';
  const jsTarget = isDesktopBuild ? 'es2022' : 'es2017';
  return {
    plugins: isDesktopBuild ? [] : [stripImportMetaUrl],
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
      // gain nothing from them.
      modulePreload: !isDesktopBuild ? false : true,
      minify: 'esbuild',
      cssMinify: true,
      reportCompressedSize: false
    }
  };
});
