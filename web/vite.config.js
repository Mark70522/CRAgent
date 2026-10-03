import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: `npm run dev` on 5173, /api proxied to the running cr-agent (7777).
// Build: `npm run build` writes into the Java resources, so `mvn package` ships the UI at http://127.0.0.1:7777/app/
// (the company machine needs no Node: build here, commit the output).
export default defineConfig({
  plugins: [react()],
  base: '/app/',
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:7777' },
  },
  build: {
    outDir: '../src/main/resources/static/app',
    emptyOutDir: true,
  },
})
