<template>
  <!-- 操作日志：关键词筛选 + 分页列表（只读） -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">操作日志</div>

    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="操作人 / 详情"
        clearable
        @keyup.enter="loadList"
      >
        <template #prefix
          ><el-icon><Search /></el-icon
        ></template>
      </el-input>
      <el-button type="primary" @click="loadList">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </div>

    <el-table :data="list" stripe>
      <el-table-column prop="createTime" label="操作时间" width="170" />
      <el-table-column prop="userName" label="操作人" width="140" />
      <el-table-column label="操作类型" width="120" align="center">
        <template #default="{ row }">
          <el-tag type="info" size="small">{{ opText(row.operation) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="操作详情" min-width="260" show-overflow-tooltip />
      <el-table-column prop="ip" label="来源IP" width="150" />
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
import { listLogs } from '@/api/log'

const loading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, keyword: '' })

// 操作类型映射：已知类型转中文，未知类型原样展示
const OP_MAP = {
  LOGIN: '登录',
  BORROW: '借书',
  RETURN: '还书',
  RENEW: '续借',
  PAY: '罚金缴纳',
  COMPENSATE: '赔偿登记'
}
const opText = (o) => OP_MAP[o] || o || ''

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.keyword) params.keyword = query.keyword
    const res = await listLogs(params)
    list.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.keyword = ''
  query.page = 1
  loadList()
}

loadList()
</script>
