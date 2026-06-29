import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  build: {
    // 目标浏览器：支持原生 ES 模块的现代浏览器，减小 polyfill 体积
    target: 'es2020',
    // 生成 CSS 代码分割，每个异步 chunk 的 CSS 独立加载
    cssCodeSplit: true,
    // 启用 CSS 压缩
    cssMinify: true,
    // 调整 chunk 大小告警阈值（默认 500KB 在 SPA 中过于保守）
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        // 手动拆包：把 node_modules 中的大库独立成 chunk，利用浏览器缓存
        // Vite 8 / Rolldown 要求 manualChunks 为函数，不支持对象形式
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return
          // Vue 全家桶（核心运行时极少变动，长期缓存命中率高）
          if (id.includes('/vue/') || id.includes('/vue-router/') || id.includes('/pinia/') || id.includes('/@vue/')) {
            return 'vendor-vue'
          }
          // 文档渲染（markdown-it + katex，体积大但按需加载）
          if (id.includes('markdown-it') || id.includes('katex')) {
            return 'vendor-md'
          }
          // HTTP 客户端
          if (id.includes('axios')) {
            return 'vendor-axios'
          }
          // 其余 node_modules 归入通用 vendor
          return 'vendor'
        },
        // 按模块类型拆分 CSS，避免单个 CSS 文件过大阻塞首屏渲染
        assetFileNames: (assetInfo) => {
          const name = assetInfo.name ?? ''
          if (/\.css$/i.test(name)) {
            return 'css/[name]-[hash:8][extname]'
          }
          return 'assets/[name]-[hash:8][extname]'
        },
        chunkFileNames: 'js/[name]-[hash:8].js',
        entryFileNames: 'js/[name]-[hash:8].js',
      },
    },
  },
  // 依赖预构建优化：提前打包这些库，避免冷启动时逐文件 ESM 请求风暴
  optimizeDeps: {
    include: ['vue', 'vue-router', 'pinia', 'axios', 'markdown-it'],
  },
})
