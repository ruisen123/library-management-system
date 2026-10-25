import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// Vite 配置：https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 组件按需自动引入（docs/02 迭代五要求），显著减小主包体积；
    // 函数式组件（ElMessage/ElMessageBox）与 v-loading 指令（ElLoading）在 main.js 手动兜底
    Components({
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: false
    })
  ],
  // 路径别名：@ 指向 src，页面里可用 @/api/xxx 代替 ../../../api/xxx
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // 构建配置：ECharts 依赖（echarts + zrender）体积较大，独立分块以提升缓存命中与并行下载；
  // 阈值上调至 600KB，避免每次构建出现与优化动作无关的告警噪声
  build: {
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules') && (id.includes('echarts') || id.includes('zrender'))) {
            return 'vendor-echarts'
          }
        }
      }
    }
  },
  server: {
    port: 5173,
    // 仅绑定本机回环地址：开发服务器不应暴露到局域网（vite/esbuild 存在开发期已知漏洞，见 npm audit）。
    // 如需局域网联调，临时改为 '0.0.0.0' 并在联调后还原。
    host: '127.0.0.1',
    // 代理：前端请求 /api/** 自动转发到后端 8080，规避浏览器跨域限制
    // （开发阶段若走 mock，则请求不经过此代理）
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 后端 Controller 无 /api 前缀，转发时剥离，否则请求全部 404
        rewrite: (path) => path.replace(/^\/api/, '')
      },
      // 2026-10-25：上传文件的静态访问路径（图书封面等）。后端已把 uploadDir 映射为 /upload/**，
      // 但此前 dev server 与 nginx 都只代理 /api —— 缺这条时 <img src="/upload/x.jpg"> 会 404，
      // 再被封面的「加载失败即降级为生成封面」逻辑吃掉，用户会以为上传没生效。
      '/upload': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
