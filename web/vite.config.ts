import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The dev server proxies `/api` to the local backend, so the browser sees a
 * same-origin API and no CORS policy is involved at all. That matters beyond
 * convenience: production sets an explicit `CORS_ORIGINS` and refuses to boot
 * on a wildcard, so developing against a wildcard would hide misconfiguration
 * until deploy.
 */
export default defineConfig({
  plugins: [react()],
  build: {
    /**
     * Keep the audio worklet as a real file.
     *
     * It is small enough that Vite would otherwise inline it as a
     * `data:text/javascript;base64,…` URL, and `audioWorklet.addModule()` does
     * not reliably accept a data URL across browsers. That breaks only in a
     * production build, and only once the microphone is switched on — so it
     * would pass every check short of a manual test on the built bundle.
     */
    assetsInlineLimit: (filePath: string) => (filePath.includes('capture-worklet') ? false : undefined),
  },
  server: {
    port: 5178,
    proxy: {
      '/api': {
        target: process.env.FISHMATE_API ?? 'http://localhost:8099',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
