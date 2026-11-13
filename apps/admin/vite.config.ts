import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'

export default defineConfig(({ mode }) => {
 const env = { ...loadEnv(mode, process.cwd(), ''), ...process.env }
 const https = env.XINKELINK_DEV_CERT && env.XINKELINK_DEV_KEY ? { cert: readFileSync(env.XINKELINK_DEV_CERT), key: readFileSync(env.XINKELINK_DEV_KEY) } : undefined
 return {
  plugins: [vue()],
  server: {
    host: 'localhost',
    port: 5173,
    strictPort: true,
    https,
    proxy: {
      '/api': { target: env.VITE_API_TARGET || 'http://127.0.0.1:18081', changeOrigin: true },
    },
  },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true },
 }
})
