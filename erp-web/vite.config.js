import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  build: { chunkSizeWarningLimit: 1500 },
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    css: false,
    setupFiles: ['./tests/setup.js'],
    server: { deps: { inline: ['element-plus'] } },
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,vue}'],
      exclude: ['src/main.js'],
      reporter: ['text', 'html', 'json-summary']
    }
  }
})
