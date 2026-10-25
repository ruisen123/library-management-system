<template>
  <!-- 出版社管理：出版社列表 + 新增 / 编辑 / 删除 -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">出版社管理</div>

    <div class="toolbar">
      <div style="flex: 1"></div>
      <el-button type="primary" @click="openDialog(null)">
        <el-icon><Plus /></el-icon>&nbsp;新增出版社
      </el-button>
    </div>

    <el-table :data="list" stripe>
      <el-table-column prop="name" label="出版社名称" />
      <el-table-column prop="contact" label="联系方式" width="160" />
      <el-table-column prop="address" label="地址" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="removePublisher(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.form.id ? '编辑出版社' : '新增出版社'"
      width="520px"
    >
      <el-form ref="formRef" :model="dialog.form" :rules="rules" label-width="80px">
        <el-form-item label="出版社名称" prop="name">
          <el-input v-model="dialog.form.name" maxlength="100" placeholder="请输入出版社名称" />
        </el-form-item>
        <el-form-item label="联系方式" prop="contact">
          <el-input v-model="dialog.form.contact" maxlength="50" placeholder="请输入联系方式" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="dialog.form.address" maxlength="200" placeholder="请输入地址" />
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
import { listPublishers, addPublisher, updatePublisher, deletePublisher } from '@/api/publisher'
import { msg } from '@/utils/message'

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const formRef = ref()
const dialog = reactive({ visible: false, form: {} })

const rules = {
  name: [{ required: true, message: '请输入出版社名称', trigger: 'blur' }]
}

async function loadList() {
  loading.value = true
  try {
    const res = await listPublishers()
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
    dialog.form = { id: null, name: '', contact: '', address: '' }
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
      await updatePublisher(dialog.form.id, dialog.form)
      msg.success('保存成功')
    } else {
      await addPublisher(dialog.form)
      msg.success('新增成功')
    }
    dialog.visible = false
    loadList()
  } finally {
    saving.value = false
  }
}

async function removePublisher(row) {
  try {
    await ElMessageBox.confirm(`确定删除出版社「${row.name}」吗？删除后不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  await deletePublisher(row.id)
  msg.success('删除成功')
  loadList()
}

loadList()
</script>
