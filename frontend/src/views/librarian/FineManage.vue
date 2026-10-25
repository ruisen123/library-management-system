<template>
  <!-- 罚金管理（图书管理员）：未缴/已缴筛选、线下收取后标记已缴 -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">罚金管理</div>

    <div class="toolbar">
      <el-select v-model="query.status" placeholder="缴纳状态" clearable style="width: 150px">
        <el-option label="未缴" :value="1" />
        <el-option label="已缴" :value="2" />
      </el-select>
      <el-button type="primary" @click="loadList">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <div style="flex: 1"></div>
      <span class="text-muted" style="font-size: 12px"
        >逾期罚金由还书自动登记，请线下收取后在此标记已缴；破损/遗失赔偿登记请前往「借还办理」</span
      >
    </div>

    <el-table :data="list" stripe>
      <el-table-column label="读者" width="160">
        <template #default="{ row }">
          <div>{{ row.userName }}</div>
          <div class="text-muted" style="font-size: 12px">{{ row.readerNo }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="bookTitle" label="图书" min-width="200" show-overflow-tooltip />
      <el-table-column label="类型" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="fineTypeTag(row.type)" size="small">{{ fineTypeText(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="110" align="right">
        <template #default="{ row }">
          <span :style="row.status === 1 ? 'color:#C05621;font-weight:600' : ''"
            >¥{{ Number(row.amount).toFixed(2) }}</span
          >
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'warning' : 'success'" size="small">{{
            fineStatusText(row.status)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="缴纳时间" width="170">
        <template #default="{ row }">{{ row.paidTime || '—' }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" link type="success" @click="doPay(row)">
            标记已缴
          </el-button>
          <span v-else class="text-muted" style="font-size: 12px">已结清</span>
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
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessageBox } from 'element-plus'
import { listFines, payFine } from '@/api/fine'
import { msg } from '@/utils/message'
import { fineTypeText, fineStatusText } from '@/utils/dict'

const loading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, status: '' })

function fineTypeTag(type) {
  return { 1: 'warning', 2: 'danger', 3: 'danger' }[type] || 'danger'
}

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.status !== '' && query.status != null) params.status = query.status
    const res = await listFines(params)
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

async function doPay(row) {
  try {
    await ElMessageBox.confirm(
      `确认已线下收取读者「${row.userName || row.readerNo}」的罚金 ¥${Number(row.amount).toFixed(2)} 并标记已缴吗？`,
      '标记已缴',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  try {
    const res = await payFine(row.id)
    msg.success(res.msg)
    loadList()
  } catch (e) {
    return
  }
}

loadList()
</script>
