<template>
  <!-- 登录页：①z 底层=完整图书馆照片 ②z 中层=向右渐变遮罩 ③z 顶层=右侧登录界面 -->
  <div class="login-page">
    <!-- ① 底层：完整照片（不裁切） -->
    <div class="bg-photo" :style="photoStyle"></div>
    <!-- ② 中层：向右渐变，照片向右淡出成白 -->
    <div class="bg-fade"></div>

    <!-- ③ 顶层：品牌 + 登录卡，浮在照片之上 -->
    <main class="foreground">
      <div class="brand">
        <div class="brand-badge">
          <el-icon :size="15"><Reading /></el-icon>
          <span>Spring Boot 3.2 · Vue 3 · MySQL 8</span>
        </div>
        <h1 class="brand-title">LibMS 图书借阅管理系统</h1>
        <p class="brand-sub">面向中小型图书馆的借还管理平台</p>
      </div>

      <div class="login-card">
        <div class="login-title">{{ mode === 'login' ? '欢迎登录' : '注册读者证' }}</div>
        <div class="login-sub">
          {{
            mode === 'login'
              ? '请使用系统分配的账号进入'
              : '填写信息后即可自助借阅，注册成功自动登录'
          }}
        </div>

        <template v-if="mode === 'login'">
          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            @keyup.enter="handleLogin"
          >
            <el-form-item prop="username">
              <el-input
                v-model="form.username"
                placeholder="请输入登录名"
                size="large"
                data-testid="login-username"
              >
                <template #prefix
                  ><el-icon><User /></el-icon
                ></template>
              </el-input>
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
                size="large"
                show-password
                data-testid="login-password"
              >
                <template #prefix
                  ><el-icon><Lock /></el-icon
                ></template>
              </el-input>
            </el-form-item>
            <el-button
              type="primary"
              size="large"
              style="width: 100%"
              :loading="loading"
              data-testid="login-submit"
              @click="handleLogin"
            >
              登 录
            </el-button>
          </el-form>

          <div class="mode-switch">
            <span>还没有读者证？</span>
            <el-button link type="primary" data-testid="register-toggle" @click="switchMode('register')">
              立即注册
            </el-button>
          </div>

          <template v-if="showQuickLogin">
            <el-divider style="margin: 14px 0 12px">
              <span class="divider-text">演示账号一键登录（密码 123456）</span>
            </el-divider>
            <div class="quick-list">
              <el-button class="quick-btn" data-testid="quick-login-admin" @click="quickLogin('admin')">
                <el-icon class="quick-ico"><Setting /></el-icon>
                <span class="quick-name">系统管理员</span>
                <span class="quick-acc">admin</span>
              </el-button>
              <el-button
                class="quick-btn"
                data-testid="quick-login-librarian"
                @click="quickLogin('librarian01')"
              >
                <el-icon class="quick-ico"><Tickets /></el-icon>
                <span class="quick-name">图书管理员</span>
                <span class="quick-acc">librarian01</span>
              </el-button>
              <el-button
                class="quick-btn"
                data-testid="quick-login-reader"
                @click="quickLogin('reader001')"
              >
                <el-icon class="quick-ico"><UserFilled /></el-icon>
                <span class="quick-name">读者（张三）</span>
                <span class="quick-acc">reader001</span>
              </el-button>
            </div>
          </template>
        </template>

        <template v-else>
          <el-form
            ref="registerRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
            @keyup.enter="handleRegister"
          >
            <el-form-item prop="username">
              <el-input
                v-model="registerForm.username"
                placeholder="登录名（字母开头，3-30 位）"
                size="large"
                data-testid="register-username"
              >
                <template #prefix
                  ><el-icon><User /></el-icon
                ></template>
              </el-input>
            </el-form-item>
            <el-form-item prop="realName">
              <el-input
                v-model="registerForm.realName"
                placeholder="真实姓名"
                size="large"
                data-testid="register-realname"
              >
                <template #prefix
                  ><el-icon><Avatar /></el-icon
                ></template>
              </el-input>
            </el-form-item>
            <el-form-item prop="phone">
              <el-input
                v-model="registerForm.phone"
                placeholder="手机号"
                size="large"
                maxlength="11"
                data-testid="register-phone"
              >
                <template #prefix
                  ><el-icon><Iphone /></el-icon
                ></template>
              </el-input>
            </el-form-item>
            <el-form-item prop="gender">
              <el-select v-model="registerForm.gender" size="large" style="width: 100%">
                <el-option label="性别（选填）：未知" :value="0" />
                <el-option label="男" :value="1" />
                <el-option label="女" :value="2" />
              </el-select>
            </el-form-item>
            <el-form-item prop="email">
              <el-input
                v-model="registerForm.email"
                placeholder="通知邮箱（选填，用于到期/预约邮件提醒）"
                size="large"
                maxlength="100"
                clearable
                data-testid="register-email"
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                placeholder="密码（8-30 位，含字母和数字）"
                size="large"
                show-password
                data-testid="register-password"
              >
                <template #prefix
                  ><el-icon><Lock /></el-icon
                ></template>
              </el-input>
            </el-form-item>
            <el-form-item prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                placeholder="再次输入密码"
                size="large"
                show-password
                data-testid="register-confirm"
              >
                <template #prefix
                  ><el-icon><Lock /></el-icon
                ></template>
              </el-input>
            </el-form-item>
            <el-button
              type="primary"
              size="large"
              style="width: 100%"
              :loading="registering"
              data-testid="register-submit"
              @click="handleRegister"
            >
              注册并登录
            </el-button>
          </el-form>

          <div class="mode-switch">
            <span>已有账号？</span>
            <el-button link type="primary" data-testid="back-login" @click="switchMode('login')">
              返回登录
            </el-button>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ROLE_HOME, setupDynamicRoutes } from '@/router'
import { msg } from '@/utils/message'
import { roleText } from '@/utils/dict'
import loginBg from '@/assets/login-bg.jpg'

const router = useRouter()
const userStore = useUserStore()

const photoStyle = { backgroundImage: 'url(' + loginBg + ')' }

/** 演示账号一键登录开关：开发环境 true，生产构建 false（见 .env.*） */
const showQuickLogin = import.meta.env.VITE_DEMO_QUICK_LOGIN === 'true'

const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// —— 读者自助注册（与登录同一个卡片切换，不新增页面/路由）——
const mode = ref('login')
const registerRef = ref(null)
const registering = ref(false)
const registerForm = reactive({
  username: '',
  realName: '',
  phone: '',
  gender: 0,
  email: '',
  password: '',
  confirmPassword: ''
})

function validateConfirm(rule, value, callback) {
  if (value !== registerForm.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const registerRules = {
  username: [
    { required: true, message: '请输入登录名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]{2,29}$/,
      message: '登录名需以字母开头，仅含字母、数字、下划线，长度 3-30 位',
      trigger: 'blur'
    }
  ],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  email: [
    {
      validator: (rule, value, callback) => {
        if (!value || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) callback()
        else callback(new Error('邮箱格式不正确'))
      },
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,30}$/,
      message: '密码需 8-30 位，且同时包含字母和数字',
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

function switchMode(next) {
  mode.value = next
  // 切换时清掉上一次的校验红字，避免登录页残留注册表单的错误提示
  if (next === 'login') registerRef.value?.clearValidate()
  else formRef.value?.clearValidate()
}

async function handleRegister() {
  await registerRef.value.validate(async (valid) => {
    if (!valid) return
    registering.value = true
    try {
      const user = await userStore.register({
        username: registerForm.username,
        realName: registerForm.realName,
        phone: registerForm.phone,
        gender: registerForm.gender,
        email: registerForm.email,
        password: registerForm.password
      })
      msg.success('注册成功，欢迎你，' + user.realName)
      setupDynamicRoutes(user.role)
      router.push(ROLE_HOME[user.role] || '/book-search')
    } catch (e) {
      // 错误提示已由响应拦截器统一处理
    } finally {
      registering.value = false
    }
  })
}

async function handleLogin() {
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const user = await userStore.login({ ...form })
      msg.success('欢迎回来，' + user.realName + '（' + roleText(user.role) + '）')
      setupDynamicRoutes(user.role)
      router.push(ROLE_HOME[user.role] || '/dashboard')
    } catch (e) {
      // 错误提示已由响应拦截器统一处理
    } finally {
      loading.value = false
    }
  })
}

async function quickLogin(username) {
  // 生产构建默认关闭：一键登录包含管理员账号，不能成为部署环境的提权入口
  if (!showQuickLogin) return
  form.username = username
  form.password = '123456'
  loading.value = true
  try {
    const user = await userStore.login({ username, password: '123456' })
    msg.success('欢迎回来，' + user.realName + '（' + roleText(user.role) + '）')
    setupDynamicRoutes(user.role)
    router.push(ROLE_HOME[user.role] || '/dashboard')
  } catch (e) {
    // 统一拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  /* 注册态字段较多，窄屏允许纵向滚动；横向仍裁切，避免背景图出现左右滚动条 */
  overflow-x: hidden;
  overflow-y: auto;
  background: #eaf2fb;
}

/* ===== ① 底层：完整照片 ===== */
.bg-photo {
  position: absolute;
  inset: 0;
  z-index: 0;
  background-repeat: no-repeat;
  /* 图已裁成 16:9；用 cover 铺满视口，避免 contain 在右侧/上下留出断层白边 */
  background-size: cover;
  background-position: center;
}

/* ===== ② 中层：向右渐变 ===== */
.bg-fade {
  position: absolute;
  inset: 0;
  z-index: 1;
  /* 左 1/3 完全透出照片，到 82% 已纯白：右侧文字与卡片压在干净底上，不再被书脊纹理干扰 */
  background: linear-gradient(
    to right,
    rgba(255, 255, 255, 0) 0%,
    rgba(255, 255, 255, 0.06) 30%,
    rgba(255, 255, 255, 0.38) 48%,
    rgba(255, 255, 255, 0.78) 62%,
    rgba(255, 255, 255, 0.95) 72%,
    #ffffff 82%
  );
}

/* ===== ③ 顶层：登录界面 ===== */
.foreground {
  position: relative;
  z-index: 10;
  /* border-box 让 min-height:100vh 含住上下 padding，否则页面会高出视口出现滚动条 */
  box-sizing: border-box;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  gap: 30px;
  padding: 4.5vh 5.5vw 4.5vh 0;
}

.brand {
  width: 400px;
  /* 项目无全局 border-box，这里显式声明，保证与登录卡左边缘对齐 */
  box-sizing: border-box;
  /* 品牌区落在渐变过渡带上，加一层近白半透明底 + 轻模糊，避免书脊纹理干扰小字 */
  padding: 16px 18px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.62);
  backdrop-filter: blur(3px);
  animation: fadeUp 0.55s ease both;
}
@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.brand-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 13px;
  border-radius: 999px;
  font-size: 12px;
  color: #2b6cb0;
  background: rgba(240, 247, 255, 0.9);
  border: 1px solid rgba(43, 108, 176, 0.16);
}
.brand-title {
  margin: 14px 0 0;
  font-size: 27px;
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: 0.3px;
  color: #16324f;
}
.brand-sub {
  margin: 8px 0 0;
  font-size: 13.5px;
  color: #5c7189;
}

.login-card {
  width: 400px;
  box-sizing: border-box;
  padding: 26px 26px 22px;
  border-radius: 14px;
  background: #ffffff;
  border: 1px solid #e8eff8;
  box-shadow:
    0 18px 44px rgba(23, 62, 112, 0.12),
    0 2px 8px rgba(23, 62, 112, 0.06);
  animation: fadeUp 0.55s 0.08s ease both;
}
.login-title {
  font-size: 21px;
  font-weight: 700;
  color: #1f2d3d;
  letter-spacing: 0.5px;
}
.login-sub {
  margin: 6px 0 18px;
  font-size: 12.5px;
  color: #8a94a6;
}
.divider-text {
  font-size: 11.5px;
  color: #9aa5b6;
  white-space: nowrap;
}

.mode-switch {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  margin: 10px 0 2px;
  font-size: 12.5px;
  color: #8a94a6;
}

.quick-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.quick-list .quick-btn {
  width: 100%;
  margin: 0;
  height: 40px;
  justify-content: flex-start;
  padding: 0 13px;
  border-color: #e3e8ef;
  color: #33415c;
  transition:
    border-color 0.16s ease,
    background 0.16s ease,
    transform 0.16s ease;
}
/* ElButton 会在 slot 外包一层 span，那层才是 flex 父级；不撑满则账号名无法右对齐 */
.quick-list .quick-btn :deep(> span) {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
}
.quick-list .quick-btn:hover {
  border-color: var(--el-color-primary);
  background: #f5f9ff;
  transform: translateY(-1px);
}
.quick-ico {
  color: #5b7ba5;
}
.quick-name {
  font-size: 13px;
  font-weight: 500;
}
.quick-acc {
  margin-left: auto;
  font-size: 11.5px;
  color: #9aa5b6;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

/* ===== 窄屏 ===== */
@media (max-width: 1100px) {
  .bg-fade {
    background: linear-gradient(
      to top,
      rgba(234, 242, 251, 0) 0%,
      rgba(255, 255, 255, 0.8) 42%,
      #ffffff 62%
    );
  }
  .foreground {
    align-items: center;
    justify-content: flex-end;
    padding: 0 5vw 5vh;
  }
  .brand,
  .login-card {
    width: 100%;
    max-width: 400px;
  }
}
@media (max-width: 820px) {
  .brand-title {
    font-size: 22px;
  }
  .brand-badge {
    display: none;
  }
}
</style>
