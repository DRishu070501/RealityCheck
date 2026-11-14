/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// Paths served by the Spring Boot backend. In development Vite proxies them;
// in the container image nginx does the same (see nginx.conf).
const BACKEND_PATHS = ['/api', '/actuator', '/v3/api-docs', '/swagger-ui']

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  // loadEnv merges .env files with VITE_-prefixed variables from the process environment.
  const backendUrl = env.VITE_BACKEND_URL ?? 'http://localhost:8080'

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: Object.fromEntries(
        BACKEND_PATHS.map((path) => [path, { target: backendUrl, changeOrigin: true }]),
      ),
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      restoreMocks: true,
    },
  }
})
