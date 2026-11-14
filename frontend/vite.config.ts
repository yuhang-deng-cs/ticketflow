import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Same-origin in development: no CORS preflights, same URLs as behind nginx.
    proxy: {
      '/api': backend,
      '/swagger-ui': backend,
      '/v3/api-docs': backend,
      '/actuator': backend,
    },
  },
})
