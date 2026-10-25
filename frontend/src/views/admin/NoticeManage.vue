<template>
  <!-- 公告管理：状态筛选 + 分页列表 + 发布 / 撤回 / 编辑 / 删除 -->
  <div v-loading="loading" class="page-card">
    <!-- 只有管理员能编辑/撤回/删除、能看已撤回，才叫「公告管理」；馆员与读者都只是"公告" -->
    <div class="card-title">{{ isAdmin ? '公告管理' : '公告' }}</div>
    <p v-if="isReader" class="notice-lead">这里可以看到图书馆发布的全部通知，点任意一行查看全文。</p>
    <p v-else-if="!isAdmin" class="notice-lead">这里可以查看全部公告，也可以发布新通知。</p>

    <div class="toolbar">
      <template v-if="!isReader">
        <!-- 状态筛选仅管理员可见：后端对非管理员强制 status=1，馆员选「已撤回」会被静默忽略，与 canEdit 同口径 -->
        <el-select v-if="isAdmin" v-model="query.status" placeholder="公告状态" clearable>
          <el-option label="已发布" :value="1" />
          <el-option label="已撤回" :value="0" />
        </el-select>
        <el-button type="primary" @click="loadList">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </template>
      <div style="flex: 1"></div>
      <el-button v-if="canPublish" type="primary" @click="openDialog(null)">
        <el-icon><Plus /></el-icon>&nbsp;发布公告
      </el-button>
    </div>

    <el-table :data="list" stripe :class="{ 'is-clickable': isReader }" @row-click="openView">
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column v-if="!isReader" prop="publisherName" label="发布人" width="120" />
      <el-table-column v-if="!isReader" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{
            noticeStatusText(row.status)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="发布时间" width="170" />
      <el-table-column v-if="isReader" label="" width="90" align="right">
        <template #default><span class="view-hint">查看 ›</span></template>
      </el-table-column>
      <el-table-column v-if="canEdit" label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" link type="warning" @click="toggleStatus(row)"
            >撤回</el-button
          >
          <el-button v-else link type="success" @click="toggleStatus(row)">发布</el-button>
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="removeNotice(row)">删除</el-button>
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

    <!-- 读者查看公告全文 -->
    <el-dialog v-model="viewDialog.visible" :title="viewDialog.row.title" width="640px">
      <div class="view-meta">
        {{ viewDialog.row.publisherName || '图书馆' }} · {{ viewDialog.row.createTime }}
      </div>
      <div class="view-content">{{ viewDialog.row.content }}</div>
      <template #footer>
        <el-button type="primary" @click="viewDialog.visible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 发布 / 编辑弹窗 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.form.id ? '编辑公告' : '发布公告'"
      width="640px"
    >
      <el-form ref="formRef" :model="dialog.form" :rules="rules" label-width="80px">
        <el-form-item label="公告标题" prop="title">
          <el-input v-model="dialog.form.title" placeholder="请输入公告标题" />
        </el-form-item>
        <el-form-item label="公告内容" prop="content">
          <el-input
            v-model="dialog.form.content"
            type="textarea"
            :rows="5"
            placeholder="请输入公告内容"
          />
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
import { listNotices, addNotice, updateNotice, deleteNotice } from '@/api/notice'
import { msg } from '@/utils/message'
import { noticeStatusText } from '@/utils/dict'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
// 三角色共用一个路由：管理员=全功能、馆员=可发布但只能看、读者=纯只读
const isAdmin = computed(() => userStore.role === 'ADMIN')
const isReader = computed(() => userStore.role === 'READER')
/** 能发布公告的角色（后端 POST 已放开给馆员） */
const canPublish = computed(() => userStore.role === 'ADMIN' || userStore.role === 'LIBRARIAN')
/** 能编辑/撤回/删除的角色（仅管理员，防馆员互删） */
const canEdit = computed(() => userStore.role === 'ADMIN')

// 读者点开看全文
const viewDialog = reactive({ visible: false, row: {} })
function openView(row) {
  if (!isReader.value) return
  viewDialog.row = row
  viewDialog.visible = true
}

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)
const formRef = ref()
const query = reactive({ page: 1, size: 10, status: '' })
const dialog = reactive({ visible: false, form: {} })

const rules = {
  title: [{ required: true, message: '请输入公告标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入公告内容', trigger: 'blur' }]
}

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.status !== '' && query.status != null) params.status = query.status
    const res = await listNotices(params)
    list.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.status = ''
  query.page = 1
  loadList()
}

function openDialog(row) {
  if (row) {
    dialog.form = { ...row }
  } else {
    dialog.form = { id: null, title: '', content: '' }
  }
  dialog.visible = true
}

async function save() {
  try {
    await formRef.value.validate()
  } catch (e) {
    return // 校验未通过
  }
  saving.value = true
  try {
    if (dialog.form.id) {
      await updateNotice(dialog.form.id, { title: dialog.form.title, content: dialog.form.content })
      msg.success('保存成功')
    } else {
      await addNotice({ title: dialog.form.title, content: dialog.form.content })
      msg.success('发布成功')
    }
    dialog.visible = false
    loadList()
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const target = row.status === 0 ? 1 : 0
  const action = target === 1 ? '发布' : '撤回'
  try {
    await ElMessageBox.confirm(`确定${action}公告「${row.title}」吗？`, '操作确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  await updateNotice(row.id, { title: row.title, content: row.content, status: target })
  msg.success(target === 1 ? '已发布该公告' : '已撤回该公告')
  loadList()
}

async function removeNotice(row) {
  try {
    await ElMessageBox.confirm(`确定删除公告「${row.title}」吗？删除后不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  await deleteNotice(row.id)
  msg.success('删除成功')
  loadList()
}

loadList()
</script>

<style scoped>
.notice-lead {
  margin: -6px 0 14px;
  font-size: 13px;
  color: #7c8ca3;
}
.is-clickable :deep(.el-table__row) {
  cursor: pointer;
}
.view-hint {
  font-size: 12px;
  color: #2b6cb0;
}
.view-meta {
  margin: -4px 0 12px;
  font-size: 12.5px;
  color: #9aa5b6;
}
.view-content {
  max-height: 50vh;
  overflow-y: auto;
  padding: 14px 16px;
  border-radius: 8px;
  background: #f8fafd;
  font-size: 14px;
  line-height: 1.8;
  color: #33415c;
  white-space: pre-wrap;
}
</style>
