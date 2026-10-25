<template>
  <!-- 借阅记录查询 + 预约队列（馆员/管理员） -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">借阅记录查询</div>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <!-- ============ 借阅记录 ============ -->
      <el-tab-pane label="借阅记录" name="records">
        <div class="toolbar">
          <el-input
            v-model="query.keyword"
            placeholder="读者姓名 / 证号 / 书名"
            clearable
            @keyup.enter="loadList"
          >
            <template #prefix
              ><el-icon><Search /></el-icon
            ></template>
          </el-input>
          <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px">
            <el-option label="在借" :value="1" />
            <el-option label="已还" :value="2" />
            <el-option label="已赔偿" :value="3" />
          </el-select>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="借出开始日期"
            end-placeholder="借出结束日期"
            style="width: 260px"
          />
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </div>

        <el-table :data="list" stripe>
          <el-table-column label="读者" width="160">
            <template #default="{ row }">
              <div>{{ row.userName }}</div>
              <div class="text-muted" style="font-size: 12px">{{ row.readerNo }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="bookTitle" label="图书" min-width="220" show-overflow-tooltip />
          <el-table-column prop="borrowDate" label="借出日期" width="120" />
          <el-table-column prop="dueDate" label="应还日期" width="120" />
          <el-table-column label="归还日期" width="120">
            <template #default="{ row }">{{ row.returnDate || '—' }}</template>
          </el-table-column>
          <el-table-column prop="renewCount" label="续借次数" width="90" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{
                borrowStatusText(row.status)
              }}</el-tag>
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

      <!-- ============ 预约队列 ============ -->
      <el-tab-pane label="预约队列" name="reservations" data-testid="reservation-queue-tab">
        <div class="toolbar">
          <el-input
            v-model="rQuery.keyword"
            placeholder="读者姓名 / 证号 / 书名"
            clearable
            @keyup.enter="loadReservations"
          >
            <template #prefix
              ><el-icon><Search /></el-icon
            ></template>
          </el-input>
          <el-select v-model="rQuery.status" placeholder="状态" clearable style="width: 140px">
            <el-option
              v-for="(text, value) in RESERVATION_STATUS_MAP"
              :key="value"
              :label="text"
              :value="Number(value)"
            />
          </el-select>
          <el-button type="primary" @click="loadReservations">查询</el-button>
          <el-button @click="resetReservationQuery">重置</el-button>
          <el-button
            v-if="isAdmin"
            type="warning"
            plain
            :loading="expiring"
            data-testid="reservation-expire"
            @click="doExpire"
            >回收过期预约</el-button
          >
        </div>

        <el-table :data="reservationList" stripe data-testid="reservation-queue-table">
          <el-table-column label="读者" width="160">
            <template #default="{ row }">
              <div>{{ row.userName }}</div>
              <div class="text-muted" style="font-size: 12px">{{ row.readerNo }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="bookTitle" label="图书" min-width="220" show-overflow-tooltip />
          <el-table-column prop="queueNo" label="排队序号" width="100" align="center" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="reservationTagType(row.status)" size="small">{{
                reservationStatusText(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前排队位置" width="120" align="center">
            <template #default="{ row }">
              <span v-if="row.status === 1">第 {{ row.queuePosition }} 位</span>
              <span v-else-if="row.status === 2" class="reserve-ready-text">已到书</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="预约时间" width="170" />
          <el-table-column label="取书截止" width="170">
            <template #default="{ row }">{{ row.expireTime || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!reservationLoading && reservationList.length === 0"
          description="暂无预约记录"
        />
        <div class="pager">
          <el-pagination
            v-model:current-page="rQuery.page"
            v-model:page-size="rQuery.size"
            :total="reservationTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadReservations"
            @size-change="loadReservations"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { listBorrowRecords } from '@/api/borrow'
import { listReservations, expireReservations } from '@/api/reservation'
import { borrowStatusText, reservationStatusText, RESERVATION_STATUS_MAP } from '@/utils/dict'
import { useUserStore } from '@/stores/user'
import { msg } from '@/utils/message'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === 'ADMIN')

const tab = ref('records')
const loading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, keyword: '', status: '' })
// 借出日期范围（docs/03：状态/日期筛选）
const dateRange = ref([])

function statusTagType(status) {
  return { 1: 'warning', 2: 'info', 3: 'danger' }[status] || 'info'
}

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.keyword) params.keyword = query.keyword
    if (query.status !== '') params.status = query.status
    if (dateRange.value && dateRange.value.length === 2) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const res = await listBorrowRecords(params)
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
  query.status = ''
  query.page = 1
  dateRange.value = []
  loadList()
}

// —— 预约队列 ——
const reservationLoading = ref(false)
const reservationList = ref([])
const reservationTotal = ref(0)
const expiring = ref(false)
const rQuery = reactive({ page: 1, size: 10, keyword: '', status: '' })

function reservationTagType(status) {
  return { 1: 'warning', 2: 'success', 3: 'info', 4: 'info', 5: 'danger' }[status] || 'info'
}

async function loadReservations() {
  reservationLoading.value = true
  try {
    const params = { page: rQuery.page, size: rQuery.size }
    if (rQuery.keyword) params.keyword = rQuery.keyword
    if (rQuery.status !== '') params.status = rQuery.status
    const res = await listReservations(params)
    reservationList.value = res.data.records || []
    reservationTotal.value = Number(res.data.total || 0)
  } catch (e) {
    // 拦截器已提示
    reservationList.value = []
    reservationTotal.value = 0
  } finally {
    reservationLoading.value = false
  }
}

function resetReservationQuery() {
  rQuery.keyword = ''
  rQuery.status = ''
  rQuery.page = 1
  loadReservations()
}

async function doExpire() {
  if (expiring.value) return
  try {
    await ElMessageBox.confirm(
      '将回收所有已超过取书截止时间的待取预约，并顺延给下一位排队读者。确定执行？',
      '回收过期预约',
      { type: 'warning', confirmButtonText: '执行回收', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  expiring.value = true
  try {
    const res = await expireReservations()
    msg.success(res.msg || '已回收过期预约')
    await loadReservations()
  } catch (e) {
    // 拦截器已提示
  } finally {
    expiring.value = false
  }
}

function onTabChange(name) {
  if (name === 'reservations' && reservationList.value.length === 0) {
    loadReservations()
  }
}

loadList()
</script>
