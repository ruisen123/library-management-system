<template>
  <!-- 我的借阅：在借 / 历史 / 罚金 三页签 -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">我的借阅</div>

    <el-tabs v-model="tab" data-testid="myborrow-tabs" @tab-change="onTabChange">
      <!-- ============ 在借 ============ -->
      <el-tab-pane label="在借" name="borrowing">
        <el-table :data="borrowingList" stripe data-testid="myborrow-borrowing-table">
          <el-table-column prop="bookTitle" label="书名" min-width="220" show-overflow-tooltip />
          <el-table-column prop="borrowDate" label="借出日期" width="120" />
          <el-table-column label="应还日期" width="180">
            <template #default="{ row }">
              {{ row.dueDate }}
              <span v-if="remainDays(row.dueDate) < 0" class="text-danger" style="margin-left: 6px"
                >逾期{{ -remainDays(row.dueDate) }}天</span
              >
              <span
                v-else-if="remainDays(row.dueDate) <= 7"
                class="text-warning"
                style="margin-left: 6px"
                >剩{{ remainDays(row.dueDate) }}天</span
              >
            </template>
          </el-table-column>
          <el-table-column prop="renewCount" label="续借次数" width="90" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag type="warning" size="small">{{ borrowStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="remainDays(row.dueDate) < 0 || row.renewCount >= 1"
                @click="doRenew(row)"
                >续借</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && borrowingList.length === 0" description="暂无在借图书" />
        <div v-if="borrowingTotal > 10" class="pager">
          <el-pagination
            v-model:current-page="borrowingPage"
            :page-size="10"
            :total="borrowingTotal"
            layout="total, prev, pager, next"
            @current-change="loadBorrowing"
          />
        </div>
      </el-tab-pane>

      <!-- ============ 预约 ============ -->
      <el-tab-pane label="预约" name="reservations" data-testid="myborrow-reservations-tab">
        <div v-if="readyReservationCount > 0" class="reserve-ready-tip">
          <el-icon class="reserve-ready-tip__icon"><BellFilled /></el-icon>
          <span
            >有 <b>{{ readyReservationCount }}</b> 本预约已到书，请于截止日期前到馆取书，逾期将顺延给下一位</span
          >
        </div>
        <el-table :data="reservationList" stripe data-testid="myborrow-reservations-table">
          <el-table-column prop="bookTitle" label="书名" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="reservationStatusTag(row.status)" size="small">{{
                reservationStatusText(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="排队位置" width="100" align="center">
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
          <el-table-column label="操作" width="100" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 1 || row.status === 2"
                link
                type="danger"
                data-testid="reservation-cancel"
                @click="doCancelReservation(row)"
                >取消预约</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!loading && reservationList.length === 0"
          description="暂无预约，图书被借光时可预约排队"
        />
        <div v-if="reservationTotal > 10" class="pager">
          <el-pagination
            v-model:current-page="reservationPage"
            :page-size="10"
            :total="reservationTotal"
            layout="total, prev, pager, next"
            @current-change="loadReservations"
          />
        </div>
      </el-tab-pane>

      <!-- ============ 历史 ============ -->
      <el-tab-pane label="历史" name="history">
        <el-table :data="historyList" stripe data-testid="myborrow-history-table">
          <el-table-column prop="bookTitle" label="书名" min-width="220" show-overflow-tooltip />
          <el-table-column prop="borrowDate" label="借出日期" width="120" />
          <el-table-column prop="dueDate" label="应还日期" width="120" />
          <el-table-column label="归还日期" width="120">
            <template #default="{ row }">{{ row.returnDate || '—' }}</template>
          </el-table-column>
          <el-table-column prop="renewCount" label="续借次数" width="90" align="center" />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{
                borrowStatusText(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && historyList.length === 0" description="暂无历史记录" />
        <div v-if="historyTotal > 10" class="pager">
          <el-pagination
            v-model:current-page="historyPage"
            :page-size="10"
            :total="historyTotal"
            layout="total, prev, pager, next"
            @current-change="loadHistory"
          />
        </div>
      </el-tab-pane>

      <!-- ============ 罚金 ============ -->
      <el-tab-pane label="罚金" name="fines" data-testid="myborrow-fines-tab">
        <!-- 待缴汇总：一眼看到欠多少；收款由馆员在罚金管理办理 -->
        <div v-if="unpaidCount" class="fine-summary">
          <div class="fine-summary__text">
            <el-icon class="fine-summary__icon"><WarningFilled /></el-icon>
            <span
              >待缴 <b>{{ unpaidCount }}</b> 笔，合计
              <b class="fine-summary__amount">¥{{ unpaidTotal }}</b
              ><span v-if="unpaidTruncated">（合计仅统计前 {{ unpaidFines.length }} 笔）</span></span
            >
          </div>
          <el-tag type="warning" effect="plain" size="small">请到馆缴纳</el-tag>
        </div>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="罚金由馆员收款后标记已缴，缴纳后自动解除借阅限制；读者不能自助标记已缴。"
          style="margin-bottom: 12px"
        />

        <el-table :data="fineList" stripe>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-tag :type="fineTypeTag(row.type)" size="small">{{
                fineTypeText(row.type)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
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
          <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
          <el-table-column label="缴纳时间" width="170">
            <template #default="{ row }">{{ row.paidTime || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!loading && fineList.length === 0"
          description="暂无罚金记录，保持良好借阅习惯"
        />
        <div v-if="fineTotal > 10" class="pager">
          <el-pagination
            v-model:current-page="finePage"
            :page-size="10"
            :total="fineTotal"
            layout="total, prev, pager, next"
            @current-change="loadFines"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { listMyBorrows, renewBook } from '@/api/borrow'
import { listFines } from '@/api/fine'
import { listMyReservations, cancelReservation } from '@/api/reservation'
import { useUserStore } from '@/stores/user'
import { msg } from '@/utils/message'
import {
  borrowStatusText,
  fineTypeText,
  fineStatusText,
  reservationStatusText
} from '@/utils/dict'

const userStore = useUserStore()

const route = useRoute()
// 支持从首页摘要条直达某个页签：/my-borrow?tab=fines
const initTab = String(route.query.tab || '')
const tab = ref(
  ['borrowing', 'reservations', 'history', 'fines'].includes(initTab) ? initTab : 'borrowing'
)
const loading = ref(false)
/** 提交防重：续借请求期间置位，防止快速连点产生重复请求（与 BorrowHandle.vue 口径一致） */
const submitting = ref(false)
const borrowingList = ref([])
const borrowingPage = ref(1)
const borrowingTotal = ref(0)
const historyList = ref([])
const historyPage = ref(1)
const historyTotal = ref(0)
const fineList = ref([])
const finePage = ref(1)
const fineTotal = ref(0)
const reservationList = ref([])
const reservationPage = ref(1)
const reservationTotal = ref(0)
const readyReservationCount = ref(0)
/** 待缴汇总独立取一页（size=1000），不随表格当前页变化 */
const unpaidFines = ref([])
/** 待缴笔数取服务端 total（与侧边栏红字同源），不受 1000 条上限截断 */
const unpaidCount = ref(0)
const unpaidTotal = ref('0.00')
/** 笔数超过单页 1000 条时，合计金额只是前 1000 笔的和，界面须如实标注 */
const unpaidTruncated = computed(() => unpaidCount.value > unpaidFines.value.length)

/** 本地 'YYYY-MM-DD' */
function todayStr() {
  const t = new Date()
  const m = String(t.getMonth() + 1).padStart(2, '0')
  const d = String(t.getDate()).padStart(2, '0')
  return `${t.getFullYear()}-${m}-${d}`
}

/** 剩余天数：<0 表示已逾期（取绝对值即逾期天数） */
function remainDays(dueDate) {
  return Math.floor((new Date(dueDate) - new Date(todayStr())) / 86400000)
}

function statusTagType(status) {
  return { 1: 'warning', 2: 'info', 3: 'danger' }[status] || 'info'
}

function fineTypeTag(type) {
  return { 1: 'warning', 2: 'danger', 3: 'danger' }[type] || 'danger'
}

function reservationStatusTag(status) {
  return { 1: 'warning', 2: 'success', 3: 'info', 4: 'info', 5: 'danger' }[status] || 'info'
}

/** 竞态防护：Tab 快速切换时丢弃过期响应，避免后发先至覆盖新数据 */
let loadSeq = 0

async function loadBorrowing(page = borrowingPage.value) {
  const seq = ++loadSeq
  borrowingPage.value = page
  loading.value = true
  try {
    // 服务端分页：此前不传 page/size 会被后端默认截断为 10 条且没有分页器，
    // 读者额度可被调到 50，超过 10 本时书架会静默漏显示（与顶部摘要矛盾）。
    const res = await listMyBorrows({ page, size: 10, status: 1 })
    if (seq !== loadSeq) return
    borrowingList.value = res.data.records || []
    borrowingTotal.value = Number(res.data.total || 0)
  } catch (e) {
    // 401 等业务错误已在 request.js 统一提示并跳转登录，此处仅兜底避免未处理拒绝
    if (seq === loadSeq) {
      borrowingList.value = []
      borrowingTotal.value = 0
    }
  } finally {
    loading.value = false
  }
}

async function loadHistory(page = historyPage.value) {
  const seq = ++loadSeq
  historyPage.value = page
  loading.value = true
  try {
    // history=true 由后端排除"在借"，直接返回服务端分页结果
    const res = await listMyBorrows({ page, size: 10, history: true })
    if (seq !== loadSeq) return
    historyList.value = res.data.records || []
    historyTotal.value = Number(res.data.total || 0)
  } catch (e) {
    if (seq === loadSeq) {
      historyList.value = []
      historyTotal.value = 0
    }
  } finally {
    loading.value = false
  }
}

async function loadFines(page = finePage.value) {
  const seq = ++loadSeq
  finePage.value = page
  loading.value = true
  try {
    const res = await listFines({ page, size: 10 })
    if (seq !== loadSeq) return
    fineList.value = res.data.records || []
    fineTotal.value = Number(res.data.total || 0)
  } catch (e) {
    if (seq === loadSeq) {
      fineList.value = []
      fineTotal.value = 0
    }
  } finally {
    loading.value = false
  }
}

/** 待缴汇总：列表取一页（上限 1000 笔）用于求和，笔数用服务端 total（不受截断） */
async function loadUnpaidSummary() {
  try {
    const res = await listFines({ page: 1, size: 1000, status: 1 })
    const rows = res.data.records || []
    unpaidFines.value = rows
    // 笔数与侧边栏红字（stores/user.js 取同一接口的 total）同源，避免同屏两个数字互相矛盾
    unpaidCount.value = Number((res.data && res.data.total) || rows.length)
    unpaidTotal.value = rows.reduce((sum, f) => sum + Number(f.amount || 0), 0).toFixed(2)
  } catch (e) {
    unpaidFines.value = []
    unpaidCount.value = 0
    unpaidTotal.value = '0.00'
  }
}

async function loadReservations(page = reservationPage.value) {
  const seq = ++loadSeq
  reservationPage.value = page
  loading.value = true
  try {
    const res = await listMyReservations({ page, size: 10 })
    if (seq !== loadSeq) return
    reservationList.value = res.data.records || []
    reservationTotal.value = Number(res.data.total || 0)
    // 待取数量单独取 total（不受当前页限制），供顶部提示与侧边栏红点使用
    const ready = await listMyReservations({ page: 1, size: 1, status: 2 })
    if (seq !== loadSeq) return
    readyReservationCount.value = Number((ready.data && ready.data.total) || 0)
    userStore.reservationReadyCount = readyReservationCount.value
  } catch (e) {
    if (seq === loadSeq) {
      reservationList.value = []
      reservationTotal.value = 0
      readyReservationCount.value = 0
    }
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function doCancelReservation(row) {
  if (submitting.value) return
  try {
    await ElMessageBox.confirm(
      `确定取消《${row.bookTitle}》的预约吗？取消后需要重新排队。`,
      '取消预约',
      { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '保留' }
    )
  } catch (e) {
    return
  }
  submitting.value = true
  try {
    await cancelReservation(row.id)
    msg.success('预约已取消')
    await loadReservations(reservationPage.value)
    userStore.refreshReservationReady()
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function onTabChange(name) {
  if (name === 'borrowing') loadBorrowing(1)
  else if (name === 'reservations') loadReservations(1)
  else if (name === 'history') loadHistory(1)
  else if (name === 'fines') {
    loadFines(1)
    loadUnpaidSummary()
  }
}

// 罚金收款仅限馆员/管理员：读者可查看金额与状态，但不能自助标记已缴
// （否则可自行解除"欠费禁借"闸门，且系统没有支付凭据）。

async function doRenew(row) {
  if (submitting.value) return
  try {
    await ElMessageBox.confirm(
      '每册图书仅可续借一次，续借后借期延长 30 天，确定续借吗？',
      '续借确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  submitting.value = true
  try {
    const res = await renewBook({ recordId: row.id })
    msg.success(res.msg)
    loadBorrowing(borrowingPage.value)
  } catch (e) {
    return
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (tab.value === 'fines') {
    loadFines(1)
    loadUnpaidSummary()
  } else if (tab.value === 'history') {
    loadHistory(1)
  } else if (tab.value === 'reservations') {
    loadReservations(1)
  } else {
    loadBorrowing()
  }
})
</script>

<style scoped>
/* 罚金待缴汇总条：橙色系，与红色警示区分，避免整页都在喊 */
.fine-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  padding: 11px 16px;
  border-radius: 8px;
  background: linear-gradient(92deg, #fff8ee 0%, #fffcf7 100%);
  border: 1px solid #fbe2c4;
}
.fine-summary__text {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  color: #8a5a20;
}
.fine-summary__icon {
  color: #e08a2e;
}
.fine-summary__amount {
  font-size: 16px;
  color: #c05621;
}
/* 预约到书提示：绿色系，与罚金橙色汇总条区分 */
.reserve-ready-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 11px 16px;
  border-radius: 8px;
  background: linear-gradient(92deg, #eefaf2 0%, #f7fdf9 100%);
  border: 1px solid #cdebd9;
  font-size: 13.5px;
  color: #2f6b48;
}
.reserve-ready-tip__icon {
  color: #3c9d63;
}
.reserve-ready-tip b {
  font-size: 16px;
  color: #237a4b;
}
.reserve-ready-text {
  color: #237a4b;
  font-weight: 600;
}
</style>
