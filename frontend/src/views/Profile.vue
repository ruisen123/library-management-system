<template>
  <!-- 个人中心：资料修改 + 密码修改 -->
  <div class="page-card">
    <div class="card-title">个人中心</div>

    <el-form v-loading="loading" :model="form" label-width="90px" style="max-width: 560px">
      <el-form-item label="登录名">
        <el-input :model-value="user.username" disabled />
      </el-form-item>
      <el-form-item label="角色">
        <el-tag type="info">{{ roleText(user.role) }}</el-tag>
      </el-form-item>
      <el-form-item v-if="user.readerNo" label="读者证号">
        <el-input :model-value="user.readerNo" disabled />
      </el-form-item>
      <el-form-item v-if="user.role === 'READER'" label="借阅额度">
        <span>{{ user.borrowedCount || 0 }}/{{ user.borrowLimit ?? 5 }} 册</span>
        <span v-if="user.validUntil" class="profile-hint">
          有效期至 {{ user.validUntil }}
          <el-tag v-if="cardExpired" type="danger" size="small">已过期</el-tag>
        </span>
      </el-form-item>
      <el-form-item label="姓名" required>
        <el-input v-model="form.realName" placeholder="请输入姓名" />
      </el-form-item>
      <el-form-item label="性别">
        <el-radio-group v-model="form.gender">
          <el-radio :value="1">男</el-radio>
          <el-radio :value="2">女</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="联系电话">
        <el-input v-model="form.phone" placeholder="请输入联系电话" />
      </el-form-item>
      <template v-if="user.role === 'READER'">
        <el-form-item label="通知邮箱">
          <el-input
            v-model="form.email"
            placeholder="可选，用于到期/预约邮件提醒"
            data-testid="profile-email"
          />
        </el-form-item>
        <el-form-item label="邮件通知">
          <el-switch
            v-model="form.emailNotify"
            :active-value="1"
            :inactive-value="0"
            data-testid="profile-email-notify"
          />
        </el-form-item>
      </template>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存资料</el-button>
        <el-button @click="pwdVisible = true">修改密码</el-button>
      </el-form-item>
    </el-form>
  </div>

  <!-- 修改密码弹窗 -->
  <el-dialog v-model="pwdVisible" title="修改密码" width="480px" @closed="resetPwdForm">
    <el-form ref="pwdRef" :model="pwdForm" label-width="90px" :rules="pwdRules">
      <el-form-item label="原密码" prop="oldPassword">
        <el-input
          v-model="pwdForm.oldPassword"
          type="password"
          show-password
          placeholder="请输入原密码"
        />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="pwdForm.newPassword"
          type="password"
          show-password
          placeholder="请输入新密码"
        />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirm">
        <el-input
          v-model="pwdForm.confirm"
          type="password"
          show-password
          placeholder="请再次输入新密码"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="pwdVisible = false">取消</el-button>
      <el-button type="primary" :loading="savingPwd" @click="submitPwd">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getProfile, updateProfile, updatePassword } from '@/api/profile'
import { useUserStore } from '@/stores/user'
import { msg } from '@/utils/message'
import { roleText } from '@/utils/dict'

const userStore = useUserStore()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const savingPwd = ref(false)
const user = ref({})
const form = reactive({ realName: '', gender: 1, phone: '', email: '', emailNotify: 1 })
const cardExpired = computed(() => {
  const until = user.value.validUntil
  if (!until) return false
  // 本地日期：toISOString 在 UTC+8 的 00:00-08:00 会返回昨天，导致过期证显示"未过期"
  const t = new Date()
  const today =
    t.getFullYear() + '-' +
    String(t.getMonth() + 1).padStart(2, '0') + '-' +
    String(t.getDate()).padStart(2, '0')
  return until < today
})

async function load() {
  loading.value = true
  try {
    const res = await getProfile()
    user.value = res.data
    form.realName = res.data.realName
    form.gender = res.data.gender ?? 1
    form.phone = res.data.phone || ''
    form.email = res.data.email || ''
    form.emailNotify = res.data.emailNotify ?? 1
  } catch (e) {
    // 失败提示由响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.realName || !form.realName.trim()) {
    msg.warning('姓名不能为空')
    return
  }
  saving.value = true
  try {
    await updateProfile({ ...form })
    userStore.updateInfo({
      realName: form.realName,
      gender: form.gender,
      phone: form.phone,
      email: form.email,
      emailNotify: form.emailNotify
    })
    msg.success('保存成功')
    await load()
  } catch (e) {
    // 已由拦截器提示
  } finally {
    saving.value = false
  }
}

// —— 修改密码 ——
const pwdVisible = ref(false)
const pwdRef = ref(null)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

function resetPwdForm() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirm = ''
}

async function submitPwd() {
  await pwdRef.value.validate(async (valid) => {
    if (!valid) return
    savingPwd.value = true
    try {
      await updatePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
      pwdVisible.value = false
      // 密码指纹已变化，当前令牌立即失效；主动清态并回登录页，避免下一次请求才 401
      userStore.logout()
      msg.success('密码修改成功，请重新登录')
      router.replace('/login')
    } catch (e) {
      // 已由拦截器提示
    } finally {
      savingPwd.value = false
    }
  })
}

onMounted(load)
</script>

<style scoped>
.profile-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
</style>
