<template>
  <!-- 用户管理：读者账号 / 管理员账号 双 Tab -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">用户管理</div>

    <el-tabs v-model="tab" @tab-change="loadList">
      <!-- ============ 读者账号 ============ -->
      <el-tab-pane label="读者账号" name="reader">
        <div class="toolbar">
          <el-input
            v-model="query.keyword"
            placeholder="姓名 / 证号 / 登录名"
            clearable
            @keyup.enter="loadList"
          >
            <template #prefix
              ><el-icon><Search /></el-icon
            ></template>
          </el-input>
          <el-select v-model="query.status" placeholder="账号状态" clearable style="width: 140px">
            <el-option label="正常" :value="1" />
            <el-option label="冻结" :value="0" />
          </el-select>
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <div style="flex: 1"></div>
          <el-button type="primary" @click="openDialog(null)">
            <el-icon><Plus /></el-icon>&nbsp;新增读者
          </el-button>
        </div>

        <el-table :data="list" stripe>
          <el-table-column prop="readerNo" label="读者证号" width="120" />
          <el-table-column prop="realName" label="姓名" width="90" />
          <el-table-column label="性别" width="70" align="center">
            <template #default="{ row }">{{ genderText(row.gender) }}</template>
          </el-table-column>
          <el-table-column prop="phone" label="联系电话" width="130" />
          <el-table-column label="在借 / 额度" width="115" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.borrowedCount > 0" type="warning" effect="plain"
                >{{ row.borrowedCount }}/{{ row.borrowLimit ?? 5 }}</el-tag
              >
              <span v-else>0/{{ row.borrowLimit ?? 5 }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="email" label="通知邮箱" width="170" show-overflow-tooltip />
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{
                userStatusText(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="开户时间" width="160" />
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 1" link type="danger" @click="toggleStatus(row)"
                >冻结</el-button
              >
              <el-button v-else link type="success" @click="toggleStatus(row)">启用</el-button>
              <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
              <el-button link @click="resetPwd(row)">重置密码</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadList"
            @size-change="loadList"
          />
        </div>
      </el-tab-pane>

      <!-- ============ 管理员账号 ============ -->
      <el-tab-pane label="管理员账号" name="admin">
        <div class="toolbar">
          <el-button type="primary" @click="openDialog(null, 'staff')">
            <el-icon><Plus /></el-icon>&nbsp;新增管理员
          </el-button>
        </div>
        <el-table :data="staffList" stripe>
          <el-table-column prop="username" label="登录名" width="140" />
          <el-table-column prop="realName" label="姓名" width="120" />
          <el-table-column label="角色" width="130">
            <template #default="{ row }">{{ roleText(row.role) }}</template>
          </el-table-column>
          <el-table-column prop="phone" label="联系电话" width="140" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{
                userStatusText(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="170" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
              <el-button link @click="resetPwd(row)">重置密码</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.form.id ? '编辑用户' : '新增用户'"
      width="560px"
    >
      <el-form :model="dialog.form" label-width="90px">
        <el-form-item label="登录名" required>
          <el-input v-model="dialog.form.username" :disabled="!!dialog.form.id" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="dialog.form.realName" />
        </el-form-item>
        <el-form-item v-if="dialog.form.role === 'READER'" label="读者证号">
          <el-input v-model="dialog.form.readerNo" placeholder="如 R20260005" />
        </el-form-item>
        <el-form-item v-if="dialog.staffMode" label="角色">
          <el-radio-group v-model="dialog.form.role">
            <el-radio value="ADMIN">系统管理员</el-radio>
            <el-radio value="LIBRARIAN">图书管理员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="dialog.form.phone" />
        </el-form-item>
        <el-form-item label="通知邮箱">
          <el-input v-model="dialog.form.email" placeholder="可选，用于到期/预约邮件提醒" />
        </el-form-item>
        <template v-if="dialog.form.role === 'READER'">
          <el-form-item label="可借上限">
            <el-input-number
              v-model="dialog.form.borrowLimit"
              :min="0"
              :max="50"
              :step="1"
              data-testid="user-borrow-limit"
            />
            <span class="form-hint">册；默认 5，0 表示不可借</span>
          </el-form-item>
          <el-form-item label="最大欠费">
            <el-input-number
              v-model="dialog.form.maxUnpaid"
              :min="0"
              :max="99999999"
              :precision="2"
              :step="1"
              data-testid="user-max-unpaid"
            />
            <span class="form-hint">元；0 表示有欠费即禁借</span>
          </el-form-item>
          <el-form-item label="有效期至">
            <el-date-picker
              v-model="dialog.form.validUntil"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="留空表示长期有效"
              style="width: 100%"
              data-testid="user-valid-until"
            />
          </el-form-item>
        </template>
        <el-form-item v-if="dialog.form.role === 'READER'" label="邮件通知">
          <el-switch v-model="dialog.form.emailNotify" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="dialog.form.gender">
            <el-radio :value="0">未知</el-radio>
            <el-radio :value="1">男</el-radio>
            <el-radio :value="2">女</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { listUsers, addUser, updateUser, toggleUserStatus, resetPassword } from '@/api/user'
import { msg } from '@/utils/message'
import { genderText, userStatusText, roleText } from '@/utils/dict'

const tab = ref('reader')
const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, keyword: '', status: '' })

// 管理员 Tab：前端过滤出非读者账号（数据量小，mock 侧无需单独接口）
const staffList = computed(() => list.value.filter((u) => u.role !== 'READER'))

const dialog = reactive({ visible: false, staffMode: false, form: {} })

async function loadList() {
  loading.value = true
  try {
    // 读者 Tab：按角色分页过滤；管理员 Tab：全量拉取后再前端过滤（管理员量小，避免单页截断）
    if (tab.value === 'reader') {
      const params = { page: query.page, size: query.size, role: 'READER' }
      if (query.keyword) params.keyword = query.keyword
      if (query.status !== '') params.status = query.status
      const res = await listUsers(params)
      list.value = res.data.records
      total.value = res.data.total
    } else {
      // 管理员 Tab：仅拉取 ADMIN + LIBRARIAN 两类角色（不再全量拉取含读者），合并展示
      const [admins, librarians] = await Promise.all([
        fetchAllAdmins('ADMIN'),
        fetchAllAdmins('LIBRARIAN')
      ])
      list.value = [...admins.records, ...librarians.records]
      // total 用服务端 total 求和：此前用截断后的数组长度回填，超 100 页时数字会偏小
      total.value = admins.total + librarians.total
      if (admins.truncated || librarians.truncated) {
        // 超界不再静默截断：如实告知只显示了前 100 页
        msg.warning('管理员/馆员账号超过 10000 个，仅显示前 10000 个，请用关键词检索')
      }
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 按角色循环拉取全部分页（上限 100 页兜底），避免 size=100 截断 */
async function fetchAllAdmins(role) {
  const size = 100
  const all = []
  const first = await listUsers({ page: 1, size, role })
  all.push(...(first.data.records || []))
  const serverTotal = Number(first.data.total || 0)
  const totalPages = Math.ceil(serverTotal / size)
  const pages = Math.min(totalPages, 100)
  for (let p = 2; p <= pages; p++) {
    const r = await listUsers({ page: p, size, role })
    all.push(...(r.data.records || []))
  }
  // total 取服务端真值、truncated 标记是否被 100 页上限截断，供调用方如实提示
  return { records: all, total: serverTotal, truncated: totalPages > pages }
}

function resetQuery() {
  query.keyword = ''
  query.status = ''
  query.page = 1
  loadList()
}

function openDialog(row, mode) {
  dialog.staffMode = mode === 'staff'
  if (row) {
    dialog.form = { ...row }
  } else {
    dialog.form = {
      id: null,
      username: '',
      realName: '',
      readerNo: '',
      phone: '',
      // 与库表默认值、自助注册一致：未选择时为 0（未知），不再默认记为男性
      gender: 0,
      role: dialog.staffMode ? 'LIBRARIAN' : 'READER',
      status: 1,
      borrowedCount: 0,
      email: '',
      borrowLimit: 5,
      maxUnpaid: 0,
      validUntil: null,
      emailNotify: 1
    }
  }
  dialog.visible = true
}

async function save() {
  saving.value = true
  try {
    if (dialog.form.id) {
      await updateUser(dialog.form.id, dialog.form)
      msg.success('保存成功')
    } else {
      await addUser({ ...dialog.form, password: '123456' })
      msg.success('新增成功，初始密码 123456')
    }
    dialog.visible = false
    loadList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const tip = row.status === 1 ? '冻结后该账号将无法登录与借书，确定冻结？' : '确定启用该账号？'
  try {
    await ElMessageBox.confirm(tip, '操作确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  await toggleUserStatus(row.id)
  msg.warning(row.status === 1 ? '已冻结该读者：可还不可借' : '已启用该读者')
  loadList()
}

async function resetPwd(row) {
  try {
    await ElMessageBox.confirm('确定将该账号密码重置为 123456 吗？', '操作确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  await resetPassword(row.id)
  msg.success('已重置密码为 123456')
}

loadList()
</script>

<style scoped>
.form-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
