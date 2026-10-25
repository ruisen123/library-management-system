import axios from 'axios'
import { ElMessage } from 'element-plus'
import router, { resetDynamicRoutes } from '@/router'

/**
 * Axios 统一封装
 * 1. 请求拦截器：自动携带 token（后端拦截器据此识别身份）
 * 2. 响应拦截器：统一处理后端 Result 结构 { code, msg, data }
 *    - code=200 成功，直接返回 res，页面里 res.data 取数据
 *    - code=401 未登录/令牌过期 → 清空本地信息，踢回登录页
 *    - 其他业务失败 → 统一 ElMessage 提示，页面无需重复写
 * 3. Mock 开关：环境变量 VITE_USE_MOCK=true 时走本地 mock，
 *    无需后端即可完整演示；false 时走真实 HTTP（vite 代理到后端 8080）
 */
const useMock = import.meta.env.VITE_USE_MOCK === 'true'

/**
 * 登录失效时的额外清理钩子：由入口（main.js）在 Pinia 安装后注册。
 * 不用在此直接 import store —— 那会形成 utils/request → stores/user → api/auth → utils/request 的模块初始化环。
 */
let onUnauthorized = null

/** 注册登录失效回调（入口调用） */
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler
}

/** 登录失效统一清态：清本地存储 → 同步 Pinia 内存态 → 复位动态路由 */
function clearAuthState() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  if (onUnauthorized) onUnauthorized()
  resetDynamicRoutes()
}

const request = axios.create({
  // 接口基础路径：默认 /api（经 vite 代理到后端 8080），可用 VITE_API_BASE 覆盖
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 10000
})

if (useMock) {
  // 仅 mock 模式装配本地适配器：适配器在模块初始化时同步就位（无异步竞态），mock 层本身按需加载。
  // 非 mock 构建下该分支被静态剔除，938 行演示数据（含明文演示口令）不再进入生产产物。
  request.defaults.adapter = async (config) => {
    const { mockAdapter } = await import('./mock')
    return mockAdapter(config)
  }
}

// —— 请求拦截器 ——
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      // 约定后端从 token 请求头读取令牌
      config.headers['token'] = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

/**
 * 把业务错误体包装成 Error 对象。
 * 此前直接 reject(res)（普通对象），调用方若只写 try/finally 会触发
 * unhandledrejection（浏览器报 pageerror: Object）；包装后既能被 catch 识别，
 * 又保留 code/msg 供页面判断。
 */
function toBusinessError(res) {
  const err = new Error((res && res.msg) || '请求失败')
  err.name = 'BusinessError'
  err.code = res && res.code
  err.data = res && res.data
  err.raw = res
  return err
}

/**
 * Blob 响应可能不是文件：后端业务失败统一返回 HTTP 200 + JSON。
 * 必须在页面保存文件之前识别出来，否则会把 {"code":400,...} 存成 .xlsx，
 * 还会提示「报表已开始下载」；401 也不会跳登录。
 */
async function handleBlobResponse(response) {
  const data = response.data
  const headers = response.headers || {}
  const contentType = String(headers['content-type'] || headers['Content-Type'] || '').toLowerCase()
  const blobType = data instanceof Blob ? String(data.type || '').toLowerCase() : ''
  const isJson = data instanceof Blob &&
    (contentType.includes('application/json') || blobType.includes('application/json'))
  if (!isJson) {
    return response
  }
  let res = null
  try {
    res = JSON.parse(await data.text())
  } catch (e) {
    return response
  }
  if (res && res.code === 200) {
    // 极少见：后端以 JSON 承载真正文件（当前无此端点），按成功处理
    return response
  }
  if (res && res.code === 401) {
    clearAuthState()
    ElMessage.error(res.msg || '登录已过期，请重新登录')
    router.replace('/login')
    return Promise.reject(toBusinessError(res))
  }
  ElMessage.error((res && res.msg) || '下载失败')
  return Promise.reject(toBusinessError(res))
}

// —— 响应拦截器 ——
request.interceptors.response.use(
  async (response) => {
    // 文件下载（Excel 导出/模板）走 Blob，先判别是否被后端换成了业务错误 JSON
    if (response.config && response.config.responseType === 'blob') {
      return handleBlobResponse(response)
    }
    const res = response.data
    // 约定业务成功码为 200
    if (res && res.code === 200) {
      return res
    }
    // 未登录/令牌过期/账号冻结（http 200 承载 401：优先展示后端细粒度提示，如"账号已冻结，请联系管理员"）
    if (res && res.code === 401) {
      // 统一清态（本地存储 + Pinia 内存态 + 动态路由），再走路由跳转（避免 location.href 整页刷新丢失 SPA 状态）
      clearAuthState()
      ElMessage.error(res.msg || '登录已过期，请重新登录')
      router.replace('/login')
      return Promise.reject(toBusinessError(res))
    }
    ElMessage.error((res && res.msg) || '操作失败')
    return Promise.reject(toBusinessError(res))
  },
  async (error) => {
    // Blob 请求失败时后端仍返回 JSON（如导出范围过大/无权限），把 Blob 解析回业务错误提示
    if (error.response && error.response.config
        && error.response.config.responseType === 'blob'
        && error.response.data instanceof Blob) {
      try {
        const text = await error.response.data.text()
        const res = JSON.parse(text)
        if (res && res.code === 401) {
          clearAuthState()
          ElMessage.error(res.msg || '登录已过期，请重新登录')
          router.replace('/login')
        } else {
          ElMessage.error(res.msg || '下载失败')
        }
        return Promise.reject(toBusinessError(res))
      } catch (e) {
        // 不是 JSON 时继续走下面的通用错误处理
      }
    }
    if (error.response) {
      const status = error.response.status
      if (status === 401) {
        clearAuthState()
        ElMessage.error('请先登录')
        router.replace('/login')
      } else if (status === 403) {
        ElMessage.error('没有权限执行该操作')
      } else if (status === 413) {
        // Nginx 层 client_max_body_size 6m 直接回真实 HTTP 413（HTML 响应体），
        // 与导入上限 5MB 对不上，须按真实原因提示而不是"服务器错误"
        ElMessage.error('上传文件过大，单次导入请控制在 5MB 以内')
      } else {
        ElMessage.error('服务器错误，请稍后重试')
      }
    } else {
      ElMessage.error('网络异常，请检查网络或后端是否启动')
    }
    return Promise.reject(error)
  }
)

export default request
