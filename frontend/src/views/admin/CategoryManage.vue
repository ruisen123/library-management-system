<template>
  <!-- 分类管理：分类列表 + 新增 / 编辑 / 删除 -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">分类管理</div>

    <div class="toolbar">
      <div style="flex: 1"></div>
      <el-button type="primary" @click="openDialog(null)">
        <el-icon><Plus /></el-icon>&nbsp;新增分类
      </el-button>
    </div>

    <el-table :data="list" stripe>
      <el-table-column prop="name" label="分类名称" />
      <el-table-column prop="sort" label="排序值" width="120" align="center" />
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="removeCategory(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.form.id ? '编辑分类' : '新增分类'"
      width="480px"
    >
      <el-form ref="formRef" :model="dialog.form" :rules="rules" label-width="80px">
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="dialog.form.name" placeholder="请输入分类名称" />
        </el-form-item>
        <el-form-item label="排序值" prop="sort">
          <el-input-number v-model="dialog.form.sort" :min="0" :max="999" />
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
import { ref, reactive } from 'vue'
import { ElMessageBox } from 'element-plus'
import { listCategories, addCategory, updateCategory, deleteCategory } from '@/api/category'
import { msg } from '@/utils/message'

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const formRef = ref()
const dialog = reactive({ visible: false, form: {} })

const rules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
}

async function loadList() {
  loading.value = true
  try {
    const res = await listCategories()
    list.value = res.data
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function openDialog(row) {
  if (row) {
    dialog.form = { ...row }
  } else {
    dialog.form = { id: null, name: '', sort: 1 }
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
      await updateCategory(dialog.form.id, dialog.form)
      msg.success('保存成功')
    } else {
      await addCategory(dialog.form)
      msg.success('新增成功')
    }
    dialog.visible = false
    loadList()
  } finally {
    saving.value = false
  }
}

async function removeCategory(row) {
  try {
    await ElMessageBox.confirm(`确定删除分类「${row.name}」吗？删除后不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  await deleteCategory(row.id)
  msg.success('删除成功')
  loadList()
}

loadList()
</script>
