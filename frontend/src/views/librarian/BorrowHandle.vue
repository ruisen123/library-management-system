<template>
  <!-- 借还办理：馆员日常主战场 —— 大输入框扫码/输入 → 选中 → 确认，一屏内完成 -->
  <div class="borrow-page">
    <!-- 今日概览：上班第一眼知道今天的盘子 -->
    <div class="today-bar">
      <div class="today-item">
        <span class="today-num">{{ todayBorrow }}</span>
        <span class="today-lbl">今日借出</span>
      </div>
      <div class="today-item">
        <span class="today-num">{{ onBorrowCount }}</span>
        <span class="today-lbl">当前在借</span>
      </div>
      <div class="today-item" :class="{ 'is-warn': dueToday > 0 }">
        <span class="today-num">
          {{ dueToday }}<span
            v-if="dueTodayTruncated"
            style="color: #e6a23c; font-size: 13px"
            >+</span
          >
        </span>
        <span class="today-lbl">
          今日到期
          <span
            v-if="dueTodayTruncated"
            style="color: #e6a23c; font-size: 12px; cursor: help"
            title="在借记录超过 1000 条：此处只统计已加载的前 1000 条，实际到期的可能更多"
            >（仅前 1000 条）</span
          >
        </span>
      </div>
      <el-button link type="primary" size="small" @click="loadToday">刷新</el-button>
    </div>

    <el-tabs v-model="tab" data-testid="borrow-tabs">
      <!-- ==================== 借书 ==================== -->
      <el-tab-pane label="借书" name="borrow">
        <div class="borrow-layout">
        <div class="borrow-main">
        <section class="panel step-block">
          <div class="step-head">
            <span class="step-no">1</span>
            <span class="step-label">选择读者</span>
            <span class="step-hint">扫码枪可直接扫读者证，回车即查</span>
          </div>

          <div class="big-search">
            <el-input
              ref="readerInputRef"
              v-model="readerKeyword"
              placeholder="读者证号 / 姓名"
              size="large"
              clearable
              data-testid="borrow-reader-input"
              @keyup.enter="queryReaders"
            >
              <template #prefix
                ><el-icon><Search /></el-icon
              ></template>
            </el-input>
            <el-button
              type="primary"
              size="large"
              data-testid="borrow-reader-search"
              @click="queryReaders"
              >查询</el-button
            >
          </div>

          <!-- 候选读者：选中后自动收起，避免占屏 -->
          <el-table
            v-if="readerList.length"
            :data="readerList"
            size="small"
            max-height="200"
            highlight-current-row
            class="pick-table"
            data-testid="borrow-reader-table"
            @current-change="onSelectReader"
          >
            <el-table-column prop="readerNo" label="读者证号" width="130" />
            <el-table-column prop="realName" label="姓名" width="100" />
            <el-table-column label="性别" width="70">
              <template #default="{ row }">{{ genderText(row.gender) }}</template>
            </el-table-column>
            <el-table-column prop="phone" label="联系电话" width="140" />
            <el-table-column label="在借" width="80" align="center">
              <template #default="{ row }">{{ row.borrowedCount || 0 }}</template>
            </el-table-column>
            <el-table-column label="" min-width="70" align="right">
              <template #default>
                <span class="pick-hint">点此行选中 ›</span>
              </template>
            </el-table-column>
          </el-table>

        </section>

        <section class="panel step-block">
          <div class="step-head">
            <span class="step-no">2</span>
            <span class="step-label">选择图书</span>
            <span class="step-hint">{{
              selectedReader ? '输入书名 / ISBN，选中后确认借出' : '先选读者，再查可借图书'
            }}</span>
          </div>

          <div v-if="!selectedReader" class="step-guide">
            <el-icon><InfoFilled /></el-icon>
            <span>先在上方选择读者；选好后这里会显示可借图书</span>
          </div>

          <div class="big-search">
            <el-input
              ref="bookInputRef"
              v-model="bookKeyword"
              placeholder="书名 / 作者 / ISBN"
              size="large"
              clearable
              data-testid="borrow-book-input"
              @keyup.enter="searchBooks"
            >
              <template #prefix
                ><el-icon><Search /></el-icon
              ></template>
            </el-input>
            <el-button
              type="primary"
              size="large"
              data-testid="borrow-book-search"
              @click="searchBooks"
              >查询</el-button
            >
          </div>

          <el-table
            v-if="bookList.length"
            :data="bookList"
            size="small"
            max-height="260"
            highlight-current-row
            class="pick-table"
            data-testid="borrow-book-table"
            @current-change="onSelectBook"
          >
            <el-table-column label="封面" width="58" align="center">
              <template #default="{ row }">
                <BookCover :book="row" size="sm" />
              </template>
            </el-table-column>
            <el-table-column prop="title" label="书名" min-width="230" show-overflow-tooltip />
            <el-table-column prop="isbn" label="ISBN" width="130" />
            <el-table-column label="可借" width="70" align="center">
              <template #default="{ row }">
                <!-- 与读者端同口径：待取预约占用副本，真实可借数 = stock - reservedCount -->
                <el-tag :type="stockTagType(row)" size="small">{{ availableStock(row) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="location" label="馆藏位置" width="120" />
          </el-table>

        </section>
        </div>

        <aside class="panel reader-panel">
          <div class="reader-panel__head">
            <span class="reader-panel__title">读者卡</span>
            <span class="reader-panel__head-right">
              <el-tag
                v-if="selectedReader"
                :type="selectedReader.status === 1 ? 'success' : 'danger'"
                size="small"
              >
                {{ selectedReader.status === 1 ? '正常' : '已停用' }}
              </el-tag>
              <el-button v-if="selectedReader" link type="primary" size="small" @click="clearReader"
                >换人</el-button
              >
            </span>
          </div>

          <div v-if="!selectedReader" class="reader-empty">
            <el-icon :size="30"><UserFilled /></el-icon>
            <p>扫码或查询读者后</p>
            <p>这里显示额度与在借清单</p>
          </div>

          <template v-else>
            <div class="reader-id">
              <div class="reader-avatar">{{ (selectedReader.realName || '?').slice(0, 1) }}</div>
              <div class="reader-id__main">
                <div class="reader-id__name">{{ selectedReader.realName }}</div>
                <div class="reader-id__no">{{ selectedReader.readerNo }}</div>
              </div>
            </div>

            <div class="reader-stats">
              <div class="reader-stat" :class="{ 'is-warn': readerCardExpired }">
                <b>{{ selectedReader.borrowedCount || 0 }}<span>/{{ readerLimit }}</span></b>
                <em>在借额度</em>
              </div>
              <div class="reader-stat" :class="{ 'is-warn': readerOverdueCount > 0 }">
                <b>{{ readerOverdueCount }}</b>
                <em>逾期</em>
              </div>
              <div class="reader-stat" :class="{ 'is-warn': readerUnpaidAmount > readerMaxUnpaid }">
                <b>{{ readerUnpaidCount }}</b>
                <em>未缴罚金</em>
              </div>
            </div>

            <div class="reader-status-line">
              <el-tag v-if="readerCardExpired" type="danger" size="small"
                >借阅证已过期（{{ selectedReader.validUntil }}）</el-tag
              >
              <el-tag v-else-if="selectedReader.validUntil" type="info" size="small"
                >有效期至 {{ selectedReader.validUntil }}</el-tag
              >
              <el-tag v-if="readerUnpaidAmount > readerMaxUnpaid" type="danger" size="small"
                >欠费 ¥{{ readerUnpaidAmount.toFixed(2) }} 超过上限 ¥{{
                  readerMaxUnpaid.toFixed(2)
                }}，借书会被拦截</el-tag
              >
              <el-tag v-else-if="readerUnpaidCount > 0" type="warning" size="small"
                >欠费 ¥{{ readerUnpaidAmount.toFixed(2) }}（上限 ¥{{
                  readerMaxUnpaid.toFixed(2)
                }}，限额内可借）</el-tag
              >
              <el-tag v-else type="success" size="small">无欠费</el-tag>
              <span v-if="readerOverdueCount > 0" class="reader-overdue"
                >有 {{ readerOverdueCount }} 本逾期</span
              >
            </div>

            <div class="reader-list-head">
              <span>在借清单</span>
              <span>{{ readerActiveList.length }} 本</span>
            </div>
            <div v-loading="readerCardLoading" class="reader-list">
              <div
                v-for="r in readerActiveList"
                :key="r.id"
                class="reader-book"
                :class="{ 'is-overdue': overdueDays(r.dueDate) > 0 }"
              >
                <div class="reader-book__title" :title="r.bookTitle">{{ r.bookTitle }}</div>
                <div class="reader-book__meta">
                  <span>{{ r.dueDate }} 到期</span>
                  <span v-if="overdueDays(r.dueDate) > 0" class="reader-book__overdue"
                    >逾期 {{ overdueDays(r.dueDate) }} 天</span
                  >
                  <span v-else-if="remainDays(r.dueDate) <= 7" class="reader-book__soon"
                    >剩 {{ remainDays(r.dueDate) }} 天</span
                  >
                </div>
              </div>
              <el-empty
                v-if="!readerCardLoading && readerActiveList.length === 0"
                description="暂无在借"
                :image-size="50"
              />
            </div>
          </template>
        </aside>
        </div>

        <section class="panel confirm-bar">
          <div class="confirm-summary">
            <template v-if="selectedReader && selectedBook">
              <span class="confirm-reader">{{ selectedReader.realName }}</span>
              <el-icon class="confirm-arrow"><Right /></el-icon>
              <span class="confirm-book">《{{ selectedBook.title }}》</span>
              <span class="confirm-due">应还 {{ borrowDueDate() }}</span>
            </template>
            <template v-else>请依次选择读者与图书</template>
          </div>
          <el-button
            type="primary"
            size="large"
            :disabled="!selectedReader || !selectedBook || submitting"
            data-testid="borrow-confirm"
            @click="doBorrow"
            >确认借出</el-button
          >
        </section>
      </el-tab-pane>

      <!-- ==================== 还书 ==================== -->
      <el-tab-pane label="还书" name="return">
        <section class="panel step-block">
          <div class="step-head">
            <span class="step-no">1</span>
            <span class="step-label">选择读者</span>
            <span class="step-hint">扫码或输入后选中读者，下方列出其在借图书</span>
          </div>

          <div class="big-search">
            <el-input
              v-model="returnReaderKeyword"
              placeholder="读者证号 / 姓名"
              size="large"
              clearable
              data-testid="return-reader-input"
              @keyup.enter="queryReturnReaders"
            >
              <template #prefix
                ><el-icon><Search /></el-icon
              ></template>
            </el-input>
            <el-button
              type="primary"
              size="large"
              data-testid="return-reader-search"
              @click="queryReturnReaders"
              >查询</el-button
            >
          </div>

          <el-table
            v-if="returnReaderList.length"
            :data="returnReaderList"
            size="small"
            max-height="200"
            highlight-current-row
            class="pick-table"
            data-testid="return-reader-table"
            @current-change="onSelectReturnReader"
          >
            <el-table-column prop="readerNo" label="读者证号" width="130" />
            <el-table-column prop="realName" label="姓名" width="100" />
            <el-table-column prop="phone" label="联系电话" width="140" />
            <el-table-column label="在借" width="80" align="center">
              <template #default="{ row }">{{ row.borrowedCount || 0 }}</template>
            </el-table-column>
            <el-table-column label="" min-width="70" align="right">
              <template #default>
                <span class="pick-hint">点此行选中 ›</span>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="returnReader" class="id-card">
            <div class="id-avatar">{{ (returnReader.realName || '?').slice(0, 1) }}</div>
            <div class="id-main">
              <div class="id-name">
                {{ returnReader.realName }}
                <span class="id-no">{{ returnReader.readerNo }}</span>
              </div>
              <div class="id-meta">
                在借 <b>{{ returnList.length }}</b> 本
              </div>
            </div>
            <el-button link type="primary" @click="clearReturnReader">换人</el-button>
          </div>
        </section>

        <section class="panel step-block">
          <div class="step-head">
            <span class="step-no">2</span>
            <span class="step-label">在借图书</span>
            <span class="step-hint">点「归还」办理；逾期会提示应缴罚金</span>
          </div>

          <el-table
            v-if="returnReader"
            :data="returnList"
            size="small"
            max-height="340"
            data-testid="return-list-table"
          >
            <el-table-column prop="bookTitle" label="书名" min-width="220" show-overflow-tooltip />
            <el-table-column prop="borrowDate" label="借出日期" width="120" />
            <el-table-column label="应还日期" width="160">
              <template #default="{ row }">
                {{ row.dueDate }}
                <el-tag
                  v-if="overdueDays(row.dueDate) > 0"
                  type="danger"
                  size="small"
                  style="margin-left: 6px"
                >
                  逾期{{ overdueDays(row.dueDate) }}天
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="renewCount" label="续借次数" width="90" align="center" />
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button link type="success" data-testid="return-action" @click="doReturn(row)"
                  >归还</el-button
                >
                <el-button
                  link
                  type="primary"
                  :disabled="overdueDays(row.dueDate) > 0 || row.renewCount >= 1"
                  @click="doRenew(row)"
                >
                  续借
                </el-button>
                <el-button link type="danger" @click="openCompensate(row)">赔偿登记</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="请先选择读者" :image-size="70" />
        </section>
      </el-tab-pane>
    </el-tabs>

    <!-- 赔偿登记弹窗 -->
    <el-dialog v-model="compensate.visible" title="赔偿登记" width="480px">
      <el-form :model="compensate.form" label-width="90px">
        <el-form-item label="图书">
          <span>{{ compensate.form.bookTitle }}</span>
        </el-form-item>
        <el-form-item label="赔偿类型" required>
          <el-select v-model="compensate.form.type" style="width: 100%">
            <el-option label="破损赔偿" :value="2" />
            <el-option label="遗失赔偿" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="赔偿金额" required>
          <el-input-number
            v-model="compensate.form.amount"
            :min="0.01"
            :precision="2"
            :step="1"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="compensate.form.remark" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="compensate.visible = false">取消</el-button>
        <el-button type="primary" @click="saveCompensate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'
import BookCover from '@/components/BookCover.vue'
import { listBooks } from '@/api/book'
import { searchReaders, listBorrowRecords, borrowBook, returnBook, renewBook } from '@/api/borrow'
import { compensateFine, listFines } from '@/api/fine'
import { msg } from '@/utils/message'
import { genderText } from '@/utils/dict'

const tab = ref('borrow')
const loading = ref(false)
/** 提交防重：借/还/续借/赔偿提交期间置位，防止快速连点产生重复请求 */
const submitting = ref(false)
const MAX_BORROW = 5

const readerInputRef = ref(null)
const bookInputRef = ref(null)

// —— 借书页签 ——
const readerKeyword = ref('')
const readerList = ref([])
const selectedReader = ref(null)
/** 读者级可借上限（后端按读者返回；缺省回落到全局默认 5） */
const readerLimit = computed(() => selectedReader.value?.borrowLimit ?? MAX_BORROW)
/** 借阅证是否已过期（valid_until 为空表示长期有效；必须用本地日期，避免 toISOString 的 UTC 偏移） */
const readerCardExpired = computed(() => {
  const until = selectedReader.value?.validUntil
  return !!until && until < todayStr()
})
const bookKeyword = ref('')
const bookList = ref([])
const selectedBook = ref(null)

// —— 右栏读者卡：额度 / 逾期 / 欠费 / 在借清单 ——
const readerActiveList = ref([])
const readerUnpaidCount = ref(0)
/** 未缴罚金金额合计（策略按金额与 maxUnpaid 比较，不能只看笔数） */
const readerUnpaidAmount = ref(0)
const readerMaxUnpaid = computed(() => Number(selectedReader.value?.maxUnpaid ?? 0))
const readerOverdueCount = ref(0)
const readerCardLoading = ref(false)

// —— 还书页签 ——
const returnReaderKeyword = ref('')
const returnReaderList = ref([])
const returnReader = ref(null)
const returnList = ref([])

const compensate = reactive({ visible: false, form: {} })

// —— 今日概览（馆员无权访问 /stats，改用借阅记录自算，零权限改动） ——
const todayBorrow = ref(0)
const onBorrowCount = ref(0)
const dueToday = ref(0)
// 在借记录超过单次拉取上限（1000）时，"今日到期"只是已加载范围内的统计，
// 必须显式提示，不能让馆员误以为是全馆全量（2026-10-24 R3-D3）。
// 后端 /borrow-records 只接受 startDate/endDate（借出日期），没有 dueDate，
// 无法直接取"到期日=今天"的 total，故采用截断提示而非改造后端。
const dueTodayTruncated = ref(false)

async function loadToday() {
  const today = todayStr()
  try {
    const [a, b] = await Promise.all([
      listBorrowRecords({ page: 1, size: 1, startDate: today, endDate: today }),
      listBorrowRecords({ page: 1, size: 1000, status: 1 })
    ])
    todayBorrow.value = Number((a.data && a.data.total) || 0)
    const recs = (b.data && b.data.records) || []
    const onLoanTotal = Number((b.data && b.data.total) || 0)
    onBorrowCount.value = onLoanTotal
    dueToday.value = recs.filter((r) => r.dueDate === today).length
    dueTodayTruncated.value = onLoanTotal > recs.length
  } catch (e) {
    // 概览失败不影响主流程
  }
}

onMounted(loadToday)

let readerTimer = null
let bookTimer = null
let returnTimer = null

/** 本地 'YYYY-MM-DD'（避免 toISOString 时区偏移） */
function todayStr() {
  const t = new Date()
  const m = String(t.getMonth() + 1).padStart(2, '0')
  const d = String(t.getDate()).padStart(2, '0')
  return t.getFullYear() + '-' + m + '-' + d
}

/** 逾期天数：>0 表示已逾期 */
function overdueDays(dueDate) {
  return Math.floor((new Date(todayStr()) - new Date(dueDate)) / 86400000)
}

/** 剩余天数（负数表示已逾期） */
function remainDays(dueDate) {
  return -overdueDays(dueDate)
}

/** 借出 30 天后的应还日期（与后端 BORROW_DAYS 一致） */
function borrowDueDate() {
  const t = new Date()
  t.setDate(t.getDate() + 30)
  const m = String(t.getMonth() + 1).padStart(2, '0')
  const d = String(t.getDate()).padStart(2, '0')
  return t.getFullYear() + '-' + m + '-' + d
}

/** 真实可借数：扣掉"待取书"预约占用的副本（与 BookSearch/BookDetail 同口径） */
function availableStock(row) {
  return Math.max(0, Number(row?.stock || 0) - Number(row?.reservedCount || 0))
}

function stockTagType(row) {
  const n = availableStock(row)
  return n <= 0 ? 'danger' : n <= 2 ? 'warning' : 'success'
}

/** 右栏读者卡：在借清单 + 未缴罚金 + 逾期数（选中读者后拉一次） */
async function loadReaderCard() {
  const reader = selectedReader.value
  if (!reader) {
    readerActiveList.value = []
    readerUnpaidCount.value = 0
    readerUnpaidAmount.value = 0
    readerOverdueCount.value = 0
    return
  }
  readerCardLoading.value = true
  try {
    const [borrows, fines] = await Promise.all([
      listBorrowRecords({ userId: reader.id, status: 1, size: 100 }),
      listFines({ userId: reader.id, status: 1, page: 1, size: 1000 })
    ])
    readerActiveList.value = (borrows.data && borrows.data.records) || []
    const fineRows = (fines.data && fines.data.records) || []
    readerUnpaidCount.value = Number((fines.data && fines.data.total) || 0)
    readerUnpaidAmount.value = fineRows.reduce((sum, f) => sum + Number(f.amount || 0), 0)
    readerOverdueCount.value = readerActiveList.value.filter(
      (r) => overdueDays(r.dueDate) > 0
    ).length
  } catch (e) {
    readerActiveList.value = []
    readerUnpaidCount.value = 0
    readerUnpaidAmount.value = 0
    readerOverdueCount.value = 0
  } finally {
    readerCardLoading.value = false
  }
}

function focusBookInput() {
  nextTick(() => {
    const el = bookInputRef.value
    if (el && el.focus) el.focus()
  })
}

function focusReaderInput() {
  nextTick(() => {
    const el = readerInputRef.value
    if (el && el.focus) el.focus()
  })
}

// —— 借书：读者查询 ——
async function queryReaders() {
  if (!readerKeyword.value) {
    msg.warning('请输入读者证号或姓名')
    return
  }
  loading.value = true
  try {
    const res = await searchReaders(readerKeyword.value)
    readerList.value = res.data || []
    selectedReader.value = null
    if (readerList.value.length === 0) {
      msg.info('未找到匹配读者')
    }
  } catch (e) {
    return
  } finally {
    loading.value = false
  }
}

function onSelectReader(row) {
  if (!row) return
  selectedReader.value = row
  loadReaderCard()
  focusBookInput()
}

function clearReader() {
  selectedReader.value = null
  selectedBook.value = null
  bookList.value = []
  bookKeyword.value = ''
  readerActiveList.value = []
  readerUnpaidCount.value = 0
  readerUnpaidAmount.value = 0
  readerOverdueCount.value = 0
  focusReaderInput()
}

// —— 借书：图书查询（仅看有库存） ——
async function searchBooks() {
  if (!selectedReader.value) {
    msg.warning('请先选择读者')
    return
  }
  if (!bookKeyword.value) {
    msg.warning('请输入书名 / 作者 / ISBN')
    return
  }
  loading.value = true
  try {
    const res = await listBooks({ keyword: bookKeyword.value, onlyStock: true })
    bookList.value = res.data.records || []
    selectedBook.value = null
    if (bookList.value.length === 0) {
      msg.info('未找到可借图书')
    }
  } catch (e) {
    return
  } finally {
    loading.value = false
  }
}

function onSelectBook(row) {
  if (row) selectedBook.value = row
}

async function doBorrow() {
  if (submitting.value) return
  try {
    await ElMessageBox.confirm(
      '确定为读者「' +
        selectedReader.value.realName +
        '」借阅《' +
        selectedBook.value.title +
        '》吗？',
      '借书确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  submitting.value = true
  try {
    const res = await borrowBook({ userId: selectedReader.value.id, bookId: selectedBook.value.id })
    msg.success(res.msg)
    // 保留读者（连续给同一人借多本更省事），只清空图书选择并回焦图书框
    selectedBook.value = null
    bookList.value = []
    bookKeyword.value = ''
    await refreshReaders()
    await loadReaderCard()
    loadToday()
    focusBookInput()
  } catch (e) {
    return
  } finally {
    submitting.value = false
  }
}

/** 借书成功后重新查询读者，刷新在借数 */
async function refreshReaders() {
  if (!readerKeyword.value) return
  try {
    const res = await searchReaders(readerKeyword.value)
    readerList.value = res.data || []
    // 同步身份条上的在借数
    const hit = (readerList.value || []).find((r) => r.id === (selectedReader.value || {}).id)
    if (hit) selectedReader.value = hit
  } catch (e) {
    return
  }
}

// —— 还书 ——
async function queryReturnReaders() {
  if (!returnReaderKeyword.value) {
    msg.warning('请输入读者证号或姓名')
    return
  }
  loading.value = true
  try {
    const res = await searchReaders(returnReaderKeyword.value)
    returnReaderList.value = res.data || []
    returnReader.value = null
    returnList.value = []
  } catch (e) {
    return
  } finally {
    loading.value = false
  }
}

function onSelectReturnReader(row) {
  if (!row) return
  returnReader.value = row
  loadReturnList()
}

function clearReturnReader() {
  returnReader.value = null
  returnList.value = []
  returnReaderKeyword.value = ''
  returnReaderList.value = []
}

async function loadReturnList() {
  if (!returnReader.value) return
  loading.value = true
  try {
    const res = await listBorrowRecords({ userId: returnReader.value.id, status: 1, size: 100 })
    returnList.value = res.data.records || []
  } catch (e) {
    return
  } finally {
    loading.value = false
  }
}

async function doReturn(row) {
  if (submitting.value) return
  try {
    await ElMessageBox.confirm(
      '确定归还《' + row.bookTitle + '》吗？若已逾期将产生逾期罚金。',
      '还书确认',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  submitting.value = true
  try {
    const res = await returnBook({ recordId: row.id })
    msg.success(res.msg)
    loadReturnList()
    loadToday()
    if (selectedReader.value) loadReaderCard()
  } catch (e) {
    return
  } finally {
    submitting.value = false
  }
}

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
    loadReturnList()
    loadToday()
    if (selectedReader.value) loadReaderCard()
  } catch (e) {
    return
  } finally {
    submitting.value = false
  }
}

function openCompensate(row) {
  compensate.form = {
    recordId: row.id,
    bookTitle: row.bookTitle,
    type: 2,
    amount: 0.01,
    remark: ''
  }
  compensate.visible = true
}

async function saveCompensate() {
  if (submitting.value) return
  if (!compensate.form.amount || Number(compensate.form.amount) <= 0) {
    msg.warning('请填写赔偿金额')
    return
  }
  try {
    await ElMessageBox.confirm('确定登记《' + compensate.form.bookTitle + '》的赔偿吗？', '赔偿确认', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  submitting.value = true
  try {
    const res = await compensateFine({
      recordId: compensate.form.recordId,
      amount: compensate.form.amount,
      type: compensate.form.type,
      remark: compensate.form.remark
    })
    msg.success(res.msg)
    compensate.visible = false
    loadReturnList()
    loadToday()
    if (selectedReader.value) loadReaderCard()
  } catch (e) {
    return
  } finally {
    submitting.value = false
  }
}

// —— 输入即搜（300ms 防抖）：保留「查询」按钮以兼容扫码枪与测试断言 ——
watch(readerKeyword, (v) => {
  if (readerTimer) clearTimeout(readerTimer)
  if (!v) {
    readerList.value = []
    return
  }
  readerTimer = setTimeout(() => {
    if (!selectedReader.value) queryReaders()
  }, 300)
})

watch(bookKeyword, (v) => {
  if (bookTimer) clearTimeout(bookTimer)
  if (!v) {
    bookList.value = []
    return
  }
  bookTimer = setTimeout(() => {
    if (selectedReader.value) searchBooks()
  }, 300)
})

watch(returnReaderKeyword, (v) => {
  if (returnTimer) clearTimeout(returnTimer)
  if (!v) {
    returnReaderList.value = []
    return
  }
  returnTimer = setTimeout(() => {
    if (!returnReader.value) queryReturnReaders()
  }, 300)
})

onBeforeUnmount(() => {
  if (readerTimer) clearTimeout(readerTimer)
  if (bookTimer) clearTimeout(bookTimer)
  if (returnTimer) clearTimeout(returnTimer)
})
</script>

<style scoped>
.borrow-page {
  padding: 4px 2px 8px;
}

/* 今日概览条 */
.today-bar {
  display: flex;
  align-items: center;
  gap: 34px;
  margin-bottom: 16px;
  padding: 14px 22px;
  border-radius: 10px;
  background: linear-gradient(94deg, #eef6ff 0%, #f9fcff 100%);
  border: 1px solid #dbe9fa;
}
.today-item {
  display: flex;
  align-items: baseline;
  gap: 7px;
}
.today-num {
  font-size: 22px;
  font-weight: 700;
  color: #2b6cb0;
  line-height: 1.1;
}
.today-lbl {
  font-size: 12.5px;
  color: #7c8ca3;
}
.today-item.is-warn .today-num {
  color: #c05621;
}

.step-block {
  margin-bottom: 22px;
  transition: opacity 0.2s ease;
}
.step-block.is-disabled {
  opacity: 0.55;
}

.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: #2b6cb0;
}
.step-label {
  font-size: 14px;
  font-weight: 600;
  color: #1f2d3d;
}
.step-hint {
  font-size: 12px;
  color: #9aa5b6;
}

/* 大输入框：扫码枪场景下更好点、更好看 */
.big-search {
  display: flex;
  gap: 10px;
  max-width: 760px;
  margin-bottom: 12px;
}

.pick-table {
  max-width: 900px;
  margin-bottom: 12px;
}
.pick-hint {
  font-size: 11.5px;
  color: #b6c1d1;
}

/* 已选读者：大身份条 */
.id-card {
  display: flex;
  align-items: center;
  gap: 14px;
  max-width: 900px;
  margin: 4px 0 6px;
  padding: 14px 18px;
  border-radius: 10px;
  background: linear-gradient(96deg, #eef6ff 0%, #f8fbff 100%);
  border: 1px solid #d6e6f8;
  animation: idIn 0.22s ease both;
}
@keyframes idIn {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.id-avatar {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(140deg, #4a90d9, #2b6cb0);
}
.id-main {
  flex: 1;
  min-width: 0;
}
.id-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #16324f;
}
.id-no {
  font-size: 12.5px;
  font-weight: 400;
  color: #7c8ca3;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.id-meta {
  margin-top: 4px;
  font-size: 12.5px;
  color: #5c7189;
}
.id-meta b {
  color: #2b6cb0;
}
.id-sep {
  margin: 0 6px;
  color: #c7d2e0;
}

/* 底部确认条：只保留布局属性；视觉样式（实底/边框/圆角/内边距）统一由下方"借还页布局改造"样式块定义，
   此处不再重复声明，避免遗留与新方案矛盾的虚线框规则 */
.confirm-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.confirm-summary {
  font-size: 13.5px;
  color: #5c7189;
}
</style>

<!-- 2026-10-22 借还页布局改造：卡片分组 / 左 65% 操作 + 右 35% 读者卡 / 实底确认条 -->
<style scoped>
.borrow-page {
  /* 底部留白：避开左下角助理浮动按钮（实测原遮挡确认条摘要 1936 px²）。 */
  padding: 0 0 96px;
}
.today-bar {
  gap: 26px;
  margin-bottom: 14px;
  padding: 8px 18px;
  border-radius: 10px;
  background: linear-gradient(94deg, #eef6ff 0%, #f9fcff 100%);
  border: 1px solid #dbe9fa;
}
.today-num {
  font-size: 20px;
  line-height: 1.15;
}
.today-lbl {
  font-size: 12px;
}
.today-bar .el-button {
  margin-left: auto;
}

.panel {
  box-sizing: border-box;
  background: #fff;
  border: 1px solid #e8eef6;
  border-radius: 10px;
  box-shadow: 0 2px 10px rgba(31, 72, 130, 0.05);
  padding: 18px;
}
.borrow-layout {
  display: flex;
  align-items: stretch;
  gap: 16px;
}
.borrow-main {
  flex: 1 1 65%;
  min-width: 0;
}
.borrow-main .step-block {
  margin-bottom: 14px;
}
.borrow-main .step-block:last-child {
  margin-bottom: 0;
}
.reader-panel {
  flex: 0 0 35%;
  max-width: 420px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.step-block {
  margin-bottom: 14px;
  transition: none;
}
.step-head {
  margin-bottom: 10px;
}
.step-guide {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f4f9ff;
  border: 1px solid #dfecfb;
  color: #5c7189;
  font-size: 12.5px;
}
.big-search {
  max-width: none;
  gap: 10px;
}
.big-search .el-input {
  flex: 1;
  min-width: 0;
}
.big-search .el-button {
  width: 96px;
  flex-shrink: 0;
}
.pick-table {
  max-width: none;
  margin-bottom: 0;
}
.id-card {
  max-width: none;
}

/* ===== 右栏读者卡 ===== */
.reader-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.reader-panel__title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2d3d;
}
.reader-panel__head-right {
  display: flex;
  align-items: center;
  gap: 6px;
}
.reader-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  border: 1px dashed #dbe7f5;
  border-radius: 9px;
  background: #fafcff;
  color: #9aa5b6;
  text-align: center;
  line-height: 1.9;
  font-size: 12.5px;
}
.reader-empty p {
  margin: 0;
}
.reader-id {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px dashed #e8eef6;
}
.reader-avatar {
  flex-shrink: 0;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(140deg, #4a90d9, #2b6cb0);
}
.reader-id__main {
  min-width: 0;
}
.reader-id__name {
  font-size: 16px;
  font-weight: 600;
  color: #16324f;
  line-height: 1.35;
}
.reader-id__no {
  margin-top: 2px;
  font-size: 12px;
  color: #7c8ca3;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.reader-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 13px 0 10px;
}
.reader-stat {
  padding: 8px 4px;
  border-radius: 8px;
  background: #f7fafe;
  border: 1px solid #eaf1fa;
  text-align: center;
}
.reader-stat b {
  display: block;
  font-size: 17px;
  line-height: 1.25;
  color: #2b6cb0;
}
.reader-stat b span {
  font-size: 11px;
  font-weight: 400;
  color: #9aa5b6;
}
.reader-stat em {
  display: block;
  margin-top: 2px;
  font-style: normal;
  font-size: 11px;
  color: #8a94a6;
}
.reader-stat.is-warn b {
  color: #c05621;
}
.reader-status-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 22px;
  margin-bottom: 8px;
}
.reader-overdue {
  font-size: 11.5px;
  font-weight: 600;
  color: #c05621;
}
.reader-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px dashed #e8eef6;
  font-size: 12px;
  color: #7c8ca3;
}
.reader-list {
  flex: 1;
  min-height: 100px;
  max-height: 46vh;
  overflow-y: auto;
}
.reader-book {
  padding: 9px 0;
  border-bottom: 1px dashed #eef2f8;
}
.reader-book:last-child {
  border-bottom: none;
}
.reader-book__title {
  font-size: 13px;
  font-weight: 500;
  color: #1f2d3d;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.reader-book__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 3px;
  font-size: 11.5px;
  color: #8a94a6;
}
.reader-book__overdue {
  color: #c05621;
  font-weight: 600;
}
.reader-book__soon {
  color: #b7791f;
}

/* ===== 底部确认条：实底卡片，按钮右对齐 ===== */
.confirm-bar {
  max-width: none;
  margin-top: 16px;
  padding: 14px 18px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e1eaf5;
  box-shadow: 0 4px 14px rgba(31, 72, 130, 0.08);
}
.confirm-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  overflow: visible;
  white-space: normal;
}
.confirm-reader {
  font-size: 14px;
  font-weight: 600;
  color: #16324f;
}
.confirm-arrow {
  color: #b6c1d1;
}
.confirm-book {
  font-size: 14px;
  font-weight: 600;
  color: #2b6cb0;
}
.confirm-due {
  font-size: 12.5px;
  color: #8a94a6;
}
.confirm-bar .el-button {
  flex-shrink: 0;
  min-width: 112px;
}

@media (max-width: 1200px) {
  .borrow-layout {
    flex-direction: column;
  }
  .reader-panel {
    flex: none;
    width: 100%;
    max-width: none;
    min-height: 0;
  }
  .reader-list {
    max-height: 300px;
  }
}
@media (max-width: 640px) {
  .big-search {
    flex-wrap: wrap;
  }
  .big-search .el-button {
    width: 100%;
  }
  .today-bar {
    gap: 16px;
    flex-wrap: wrap;
  }
}
</style>
