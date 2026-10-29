import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import { VitePWA } from 'vite-plugin-pwa'

// During development the API runs separately; set BACKEND_URL if it is not on 8080.
const backend = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['apple-touch-icon.png'],
      manifest: {
        name: 'Home Display',
        short_name: 'Home',
        description: 'Weather, tasks, calendar and lights for the home',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        background_color: '#0f1419',
        theme_color: '#0f1419',
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Only the app shell is cached. API data is never served from the service worker.
        navigateFallback: '/index.html',
        globIgnores: ['**/*.wasm'],
        navigateFallbackDenylist: [/^\/api\//, /^\/agent\//],
        // The wake word's models and runtime are too big to cache up front; keep them once they have been fetched, so the
        // door screen can still hear its wake word when the server is briefly out of reach.
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith('/models/') || url.pathname.endsWith('.wasm'),
            handler: 'CacheFirst',
            options: { cacheName: 'wake-word', cacheableResponse: { statuses: [200] } },
          },
        ],
      },
    }),
  ],
  server: {
    proxy: {
      // ws: the voice conversation is a WebSocket under /api
      '/api': { target: backend, ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
})
