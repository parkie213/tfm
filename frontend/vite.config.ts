import { defineConfig } from 'vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'

// Pin to 5174 (strict): 5173 is used by another local dev server on this machine.
// The dev Envoy proxy's CORS allow-list must match this origin.
export default defineConfig({
  publicDir: false,
  plugins: [
    // Under Vite 8 / Rolldown the standard React plugin transforms via Oxc (native, faster than
    // the old SWC plugin) and is the supported path for React Compiler.
    react(),
    // React Compiler — auto-memoises components/hooks (React 19 runtime). Runs Babel only on the
    // files reactCompilerPreset's filter matches, so the Oxc fast path still handles everything else.
    babel({ presets: [reactCompilerPreset()] }),
  ],
  build: {
    rolldownOptions: {
      output: {
        // Split the heavy, rarely-changing vendor code into its own chunk so an app-code change doesn't
        // bust its cache (and it downloads in parallel). maplibre splits itself out via the lazy
        // StationMapGL/TVView imports, so it isn't listed here.
        codeSplitting: {
          groups: [
            { name: 'react-vendor', test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/ },
          ],
        },
      },
    },
  },
  server: {
    port: 5174,
    strictPort: true,
  },
  preview: {
    port: 4174,
    strictPort: true,
  },
})
