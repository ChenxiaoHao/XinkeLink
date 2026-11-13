import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

export default defineConfig(env => mergeConfig(typeof viteConfig === 'function' ? viteConfig(env) : viteConfig, {
  test: {
    include: ['tests/**/*.test.ts'],
  },
}))
