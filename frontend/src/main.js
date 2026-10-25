/**
 * 应用入口
 * 装配顺序：Pinia → Router → 全局图标 → 挂载
 * Element Plus 组件按需引入（vite 插件处理模板组件）；
 * 函数式组件（ElMessage/ElMessageBox）与 v-loading 指令（ElLoading）此处手动兜底注册与样式。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElLoading } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { useUserStore } from './stores/user'
import { setUnauthorizedHandler } from './utils/request'
import './styles/index.css'

const app = createApp(App)

// 全局注册 Element Plus 图标（页面中直接写 <el-icon><Reading /></el-icon>）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
// 按需模式下 v-loading 指令需显式注册（ElLoading 服务）
app.use(ElLoading)

// 登录失效（响应拦截器 401）时同步清理 Pinia 内存态：
// 在入口注册回调，避免 utils/request 直接 import store 形成模块初始化环
setUnauthorizedHandler(() => useUserStore().logout())

app.mount('#app')
