<template>
  <!-- 图书检索：读者找书。输入即搜 + 卡片墙（封面优先），点卡片进详情 -->
  <div v-loading="loading" class="booksearch-page">
    <!-- 读者个人摘要：进门先知道自己欠什么、几本快到期 -->
    <div class="reader-hero">
      <div class="hero-greet">
        <h2 class="hero-name">{{ realName }}，欢迎回来</h2>
        <p class="hero-tip">
          <template v-if="readyReservations > 0"
            >有 <b class="warn">{{ readyReservations }}</b> 本预约已到书，请及时到馆取书</template
          >
          <template v-else-if="dueSoon > 0"
            >有 <b class="warn">{{ dueSoon }}</b> 本将在 7 天内到期，记得按时归还</template
          >
          <template v-else-if="unpaidFines > 0"
            >你有 <b class="warn">{{ unpaidFines }}</b> 笔罚金待缴纳</template
          >
          <template v-else>目前没有待办事项，祝你阅读愉快</template>
        </p>
      </div>
      <div class="hero-stats">
        <div class="hs" @click="goMyBorrow('borrowing')">
          <b>{{ onBorrow }}</b><span>在借</span>
        </div>
        <div class="hs" :class="{ 'hs-warn': unpaidFines > 0 }" @click="goMyBorrow('fines')">
          <b>{{ unpaidFines }}</b><span>待缴罚金</span>
        </div>
        <div class="hs" :class="{ 'hs-warn': dueSoon > 0 }" @click="goMyBorrow('borrowing')">
          <b>{{ dueSoon }}</b><span>7 天内到期</span>
        </div>
        <div
          class="hs"
          :class="{ 'hs-ready': readyReservations > 0 }"
          @click="goMyBorrow('reservations')"
        >
          <b>{{ readyReservations }}</b><span>待取预约</span>
        </div>
      </div>
    </div>

    <section v-if="showHot" class="hot-strip" data-testid="hot-books">
      <div class="hot-head">
        <span class="hot-title">热门借阅</span>
        <span class="hot-sub">按累计借阅次数</span>
      </div>
      <div class="hot-list">
        <article
          v-for="(row, idx) in hotBooks"
          :key="row.id"
          class="hot-card"
          data-testid="hot-book-card"
          @click="openDetail(row)"
        >
          <span class="hot-rank" :class="'hot-rank--' + (idx + 1)">{{ idx + 1 }}</span>
          <BookCover :book="row" size="sm" class="hot-cover" />
          <div class="hot-info">
            <h4 :title="row.title">{{ row.title }}</h4>
            <p>{{ row.author || '佚名' }}</p>
            <span class="hot-count">借阅 {{ row.borrowCount || 0 }} 次</span>
          </div>
        </article>
      </div>
    </section>

    <div class="search-bar">
      <div class="search-input">
        <el-input
          v-model="query.keyword"
          placeholder="搜书名、作者或 ISBN"
          size="large"
          clearable
          data-testid="search-keyword"
          @keyup.enter="searchNow"
        >
          <template #prefix
            ><el-icon><Search /></el-icon
          ></template>
        </el-input>
      </div>
      <el-select
        v-model="query.categoryId"
        placeholder="全部分类"
        size="large"
        clearable
        class="search-cat"
        @change="searchNow"
      >
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select
        v-model="query.sort"
        size="large"
        class="search-sort"
        data-testid="search-sort"
        @change="searchNow"
      >
        <el-option label="最新上架" value="" />
        <el-option label="借阅热度" value="popular" />
      </el-select>
      <el-button size="large" type="primary" data-testid="search-submit" @click="searchNow"
        >搜索</el-button
      >
    </div>

    <div class="result-head">
      <span class="result-count">
        共 <b>{{ total }}</b> 本
        <template v-if="query.keyword">· 关键词「{{ query.keyword }}」</template>
      </span>
      <span v-if="typing" class="result-typing">正在搜索…</span>
    </div>

    <div v-if="list.length" class="book-grid" data-testid="book-search-grid">
      <article
        v-for="row in list"
        :key="row.id"
        class="book-card"
        data-testid="book-card"
        @click="openDetail(row)"
      >
        <BookCover :book="row" size="lg" class="book-card__cover" />
        <div class="book-card__info">
          <h3 class="book-card__title" :title="row.title">{{ row.title }}</h3>
          <p class="book-card__author">{{ row.author || '佚名' }}</p>
          <p class="book-card__meta">
            <span class="tag-cat">{{ row.categoryName || '未分类' }}</span>
            <span class="book-card__pub" :title="row.publisherName">{{
              row.publisherName || '—'
            }}</span>
          </p>
          <p v-if="row.location" class="book-card__loc">
            <el-icon><Location /></el-icon>
            <span>{{ row.location }}</span>
          </p>
          <!-- 库存标签保持原样；只有按钮用红/蓝两色区分：
               蓝=可预约（能做），红=取消预约（要撤），不引入第三种颜色 -->
          <div class="book-card__foot">
            <el-tag v-if="availableStock(row) === 0" type="danger" size="small" effect="light"
              >无库存</el-tag
            >
            <el-tag
              v-else-if="availableStock(row) <= 2"
              type="warning"
              size="small"
              effect="light"
              >仅剩 {{ availableStock(row) }} 本</el-tag
            >
            <el-tag v-else type="success" size="small" effect="light"
              >可借 {{ availableStock(row) }} 本</el-tag
            >
            <el-button
              class="book-card__act"
              size="small"
              :type="reservationOf(row) ? 'danger' : 'primary'"
              plain
              :loading="reservingId === row.id"
              :data-testid="reservationOf(row) ? 'book-cancel' : 'book-reserve'"
              @click.stop="toggleReserve(row)"
              >{{ reservationOf(row) ? '取消预约' : '预约' }}</el-button
            >
            <span class="book-card__detail">详情 ›</span>
          </div>
        </div>
      </article>
    </div>

    <el-empty v-else description="没有找到符合条件的图书，换个关键词试试" />

    <div class="pager">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[8, 12, 24]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadList"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 图书详情：二级界面（弹窗），不跳页，看完直接关掉回到列表 -->
    <el-dialog
      v-model="detailVisible"
      width="780px"
      top="6vh"
      class="book-detail-dialog"
      data-testid="book-detail-dialog"
      destroy-on-close
    >
      <template #header>
        <span class="dlg-title">图书详情</span>
      </template>

      <div v-loading="detailLoading">
        <div class="dlg-body">
          <template v-if="detail">
            <div class="dlg-cover">
              <BookCover :book="detail" size="xl" />
            </div>

            <div class="dlg-info">
            <h2 class="dlg-book-title">{{ detail.title }}</h2>
            <p class="dlg-author">{{ detail.author || '佚名' }}</p>

            <div class="dlg-tags">
              <span class="tag-cat">{{ detail.categoryName || '未分类' }}</span>
              <span class="dlg-pub">{{ detail.publisherName || '—' }}</span>
            </div>

            <div class="dlg-stats">
              <div class="stat">
                <span class="stat-num">{{ availableStock(detail) }}</span>
                <span class="stat-lbl">可借册数</span>
              </div>
              <div class="stat">
                <span class="stat-num">{{ detail.totalStock }}</span>
                <span class="stat-lbl">馆藏总册数</span>
              </div>
              <div class="stat">
                <span class="stat-num">{{ detail.borrowCount || 0 }}</span>
                <span class="stat-lbl">累计借阅</span>
              </div>
            </div>

            <dl class="dlg-meta">
              <div class="meta-row">
                <dt>ISBN</dt>
                <dd>{{ detail.isbn }}</dd>
              </div>
              <div class="meta-row">
                <dt>出版日期</dt>
                <dd>{{ detail.publishDate || '—' }}</dd>
              </div>
              <div class="meta-row">
                <dt>定价</dt>
                <dd>{{ detail.price == null ? '—' : '¥' + Number(detail.price).toFixed(2) }}</dd>
              </div>
              <div class="meta-row">
                <dt>馆藏位置</dt>
                <dd class="meta-loc">
                  <el-icon><Location /></el-icon>{{ detail.location || '—' }}
                </dd>
              </div>
            </dl>
            </div>
          </template>
        </div>

        <div v-if="detail && detail.description" class="dlg-desc">
          <div class="dlg-desc-title">内容简介</div>
          <p class="dlg-desc-text">{{ detail.description }}</p>
        </div>

        <!-- 2026-10-25：给 BookDetail.vue 补 UI 入口 —— 该页此前「有路由、无入口」，只能手输 URL 打开。
             用新标签打开以保留弹窗的快速查看体验（history 模式 + nginx try_files 已支持直接访问该路径）。 -->
        <a
          v-if="detail && detail.id"
          class="dlg-open-full"
          :href="'/book-detail/' + detail.id"
          target="_blank"
          rel="noopener"
          data-testid="detail-open-full"
          >在新标签页打开图书详情页</a
        >
      </div>

      <template #footer>
        <el-button
          v-if="detail"
          :type="reservationOf(detail) ? 'danger' : 'primary'"
          :loading="reservingId === detail.id"
          :data-testid="reservationOf(detail) ? 'detail-cancel' : 'detail-reserve'"
          @click="toggleReserve(detail)"
          >{{ reservationOf(detail) ? '取消预约' : '预约' }}</el-button
        >
        <el-button data-testid="detail-close" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import BookCover from '@/components/BookCover.vue'
import { listBooks, getBook } from '@/api/book'
import { listCategories } from '@/api/category'
import { listMyBorrows } from '@/api/borrow'
import { listFines } from '@/api/fine'
import { reserveBook, cancelReservation, listMyReservations } from '@/api/reservation'
import { ElMessageBox } from 'element-plus'
import { msg } from '@/utils/message'

const router = useRouter()
const userStore = useUserStore()
const realName = computedSafeName()

// —— 读者个人摘要 ——
const onBorrow = ref(0)
const unpaidFines = ref(0)
const dueSoon = ref(0)
const readyReservations = ref(0)
const reservingId = ref(null)

function computedSafeName() {
  const info = userStore.userInfo || {}
  return info.realName || '读者'
}

function todayStr() {
  const t = new Date()
  const m = String(t.getMonth() + 1).padStart(2, '0')
  const d = String(t.getDate()).padStart(2, '0')
  return t.getFullYear() + '-' + m + '-' + d
}

async function loadMySummary() {
  try {
    const res = await listMyBorrows({ page: 1, size: 100, status: 1 })
    const recs = (res.data && res.data.records) || []
    onBorrow.value = Number((res.data && res.data.total) || 0)
    const today = new Date(todayStr())
    dueSoon.value = recs.filter((r) => {
      const days = Math.floor((new Date(r.dueDate) - today) / 86400000)
      return days >= 0 && days <= 7
    }).length
  } catch (e) {
    // 摘要失败不影响找书
  }
  try {
    const f = await listFines({ page: 1, size: 1, status: 1 })
    unpaidFines.value = Number((f.data && f.data.total) || 0)
  } catch (e) {
    // 同上
  }
  try {
    const r = await listMyReservations({ page: 1, size: 1, status: 2 })
    readyReservations.value = Number((r.data && r.data.total) || 0)
  } catch (e) {
    readyReservations.value = 0
  }
}

// —— 预约状态映射：bookId -> 我的有效预约，供按钮红/蓝切换 ——
// 单个读者有效预约上限 5 个（MAX_ACTIVE_PER_USER），一次拉全量开销可忽略
const myReservationMap = ref({})


/** 真实可借数 = 库存 - 已被"待取书"预约占住的副本。
 *  待取预约不扣库存（保巡检 R1 口径），所以这里单独扣，
 *  否则会显示"可借 6 本"而实际 6 本都被预留、一借就被拒。 */
function availableStock(row) {
  if (!row) return 0
  return Math.max(0, (row.stock || 0) - (row.reservedCount || 0))
}

function reservationOf(row) {
  if (!row || row.id == null) return null
  return myReservationMap.value[row.id] || null
}

async function loadMyReservations() {
  try {
    const res = await listMyReservations({ page: 1, size: 50 })
    const recs = (res.data && res.data.records) || []
    const map = {}
    for (const r of recs) {
      // 只认"仍有效"的预约；已完成 / 已取消 / 已过期的不再显示取消按钮
      if (r.status === 1 || r.status === 2) map[r.bookId] = r
    }
    myReservationMap.value = map
  } catch (e) {
    myReservationMap.value = {}
  }
}

/**
 * 预约 / 取消预约 共用一个按钮（作者 2026-10-22 要求）：
 * 未预约 -> 蓝色「预约」；已预约 -> 红色「取消预约」。
 * 取消前一律二次确认（排队中与已到书都确认）。
 */
async function toggleReserve(row) {
  if (reservingId.value) return
  const mine = reservationOf(row)

  if (mine) {
    const tip =
      mine.status === 2
        ? '《' + row.title + '》已到馆待取，取消后需要重新排队，确定取消吗？'
        : '确定取消《' + row.title + '》的预约吗？取消后需要重新排队。'
    try {
      await ElMessageBox.confirm(tip, '取消预约', {
        type: 'warning',
        confirmButtonText: '确定取消',
        cancelButtonText: '再想想'
      })
    } catch (e) {
      return // 用户点了"再想想"
    }
    reservingId.value = row.id
    try {
      await cancelReservation(mine.id)
      msg.success('预约已取消')
      await loadMyReservations()
      await loadList()
      await loadMySummary()
    } catch (e) {
      // 拦截器已提示
    } finally {
      reservingId.value = null
    }
    return
  }

  reservingId.value = row.id
  try {
    const res = await reserveBook({ bookId: row.id })
    msg.success(res.msg)
    // 不再自动关闭详情弹窗：让读者当场看到按钮由蓝变红
    await loadMyReservations()
    await loadList()
    await loadMySummary()
  } catch (e) {
    // 拦截器已提示
  } finally {
    reservingId.value = null
  }
}

/** 跳到我的书架并直达对应页签：在借 / 历史 / 罚金 */
function goMyBorrow(tabName) {
  router.push({ path: '/my-borrow', query: { tab: tabName || 'borrowing' } })
}

const loading = ref(false)
const typing = ref(false)
const list = ref([])
const total = ref(0)
const categories = ref([])
const query = reactive({ page: 1, size: 8, keyword: '', categoryId: '', sort: '' })

const hotBooks = ref([])
const showHot = computed(
  () =>
    hotBooks.value.length > 0 &&
    !query.keyword &&
    !query.categoryId &&
    !query.sort &&
    query.page === 1
)

let debounceTimer = null

async function loadHot() {
  try {
    const res = await listBooks({ page: 1, size: 5, sort: 'popular' })
    hotBooks.value = res.data.records || []
  } catch (e) {
    hotBooks.value = []
  }
}

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.keyword) params.keyword = query.keyword
    if (query.categoryId !== '' && query.categoryId !== undefined) {
      params.categoryId = query.categoryId
    }
    if (query.sort) params.sort = query.sort
    const res = await listBooks(params)
    list.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
    typing.value = false
  }
}

/** 输入即搜：停手 300ms 才发请求，避免每敲一个字就打一次后端 */
watch(
  () => query.keyword,
  () => {
    query.page = 1
    typing.value = true
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(loadList, 300)
  }
)

function searchNow() {
  if (debounceTimer) clearTimeout(debounceTimer)
  query.page = 1
  typing.value = false
  loadList()
}

function onSizeChange() {
  query.page = 1
  loadList()
}

// —— 图书详情弹窗（二级界面）——
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

/** 点卡片：先把已展示的字段填进去立刻出图，再拉完整详情补齐简介等字段 */
async function openDetail(row) {
  detail.value = { ...row }
  detailVisible.value = true
  detailLoading.value = true
  try {
    const res = await getBook(row.id)
    detail.value = res.data
  } catch (e) {
    // 详情拉取失败时降级用列表数据，弹窗仍可用
  } finally {
    detailLoading.value = false
  }
}

onMounted(async () => {
  loadList()
  loadHot()
  loadMySummary()
  loadMyReservations()
  try {
    const res = await listCategories()
    categories.value = res.data
  } catch (e) {
    // 加载失败由拦截器提示
  }
})

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<style scoped>
.booksearch-page {
  min-height: 320px;
}

/* ============ 读者个人摘要 ============ */
.reader-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
  margin-bottom: 18px;
  padding: 18px 24px;
  border-radius: 12px;
  background: linear-gradient(100deg, #eef6ff 0%, #f7fbff 60%, #ffffff 100%);
  border: 1px solid #dfeafa;
}
.hero-name {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #16324f;
}
.hero-tip {
  margin: 6px 0 0;
  font-size: 13px;
  color: #6b7789;
}
.hero-tip .warn {
  color: #c05621;
  font-size: 15px;
}
.hero-stats {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.hs {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 78px;
  padding: 8px 12px;
  border-radius: 9px;
  background: #fff;
  border: 1px solid #e6eef8;
  cursor: pointer;
  transition:
    box-shadow 0.16s ease,
    transform 0.16s ease;
}
.hs:hover {
  box-shadow: 0 4px 12px rgba(31, 72, 130, 0.1);
  transform: translateY(-1px);
}
.hs b {
  font-size: 20px;
  font-weight: 700;
  color: #2b6cb0;
  line-height: 1.2;
}
.hs span {
  margin-top: 2px;
  font-size: 11.5px;
  color: #8a94a6;
}
.hs-warn b {
  color: #c05621;
}
.hs-ready b {
  color: #237a4b;
}
@media (max-width: 820px) {
  .reader-hero {
    flex-direction: column;
    align-items: flex-start;
  }
}

/* ============ 热门借阅 ============ */
.hot-strip {
  margin-bottom: 18px;
  padding: 14px 16px;
  border-radius: 12px;
  background: linear-gradient(100deg, #fff8ed 0%, #fffdf9 55%, #ffffff 100%);
  border: 1px solid #f3e2c8;
}
.hot-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}
.hot-title {
  font-size: 15px;
  font-weight: 700;
  color: #16324f;
}
.hot-sub {
  font-size: 12px;
  color: #9a8a70;
}
.hot-list {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.hot-card {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1 1 0;
  min-width: 210px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #efe4d4;
  cursor: pointer;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}
.hot-card:hover {
  border-color: #e2b877;
  box-shadow: 0 4px 12px rgba(160, 108, 30, 0.1);
}
.hot-rank {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  background: #c9b79a;
}
.hot-rank--1 {
  background: #e8a23d;
}
.hot-rank--2 {
  background: #9aa8b8;
}
.hot-rank--3 {
  background: #c08a62;
}
.hot-cover {
  flex-shrink: 0;
}
.hot-info {
  min-width: 0;
}
.hot-info h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #16324f;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.hot-info p {
  margin: 2px 0;
  font-size: 11.5px;
  color: #8a94a6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.hot-count {
  font-size: 11px;
  color: #2b6cb0;
}

/* ============ 搜索区 ============ */
.search-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: -4px 0 18px;
}
.search-input {
  flex: 1;
  max-width: 460px;
}
.search-cat {
  width: 150px;
}
.search-sort {
  width: 132px;
}

.result-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
  font-size: 13px;
  color: #8a94a6;
}
.result-count b {
  color: #2b6cb0;
  font-size: 15px;
}
.result-typing {
  animation: blink 1.2s ease-in-out infinite;
}
@keyframes blink {
  50% {
    opacity: 0.35;
  }
}

/* ============ 卡片墙 ============ */
.book-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
  gap: 16px;
}

.book-card {
  display: flex;
  gap: 14px;
  padding: 14px;
  border: 1px solid #edf1f7;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition:
    box-shadow 0.18s ease,
    transform 0.18s ease,
    border-color 0.18s ease;
}
.book-card:hover {
  border-color: #cfe0f5;
  box-shadow: 0 6px 18px rgba(31, 72, 130, 0.1);
  transform: translateY(-2px);
}

.book-card__cover {
  align-self: flex-start;
}
/* 悬停时封面轻微放大（覆盖 BookCover 自带的上移） */
.book-card:hover .book-card__cover {
  transform: scale(1.02);
}

.book-card__info {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}
.book-card__title {
  margin: 0;
  /* 固定两行高度：书名一行/两行不齐会让同一行卡片高低不一 */
  min-height: 2.8em;
  font-size: 14.5px;
  font-weight: 600;
  line-height: 1.4;
  color: #1f2d3d;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.book-card__author {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: #6b7789;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.book-card__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0 0;
  min-width: 0;
}
.tag-cat {
  flex-shrink: 0;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 11px;
  color: #3d6ea8;
  background: #eef4fb;
}
.book-card__pub {
  font-size: 12px;
  color: #9aa5b6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.book-card__loc {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 6px 0 0;
  font-size: 12px;
  color: #8a94a6;
}
.book-card__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 10px;
}
/* 预约/取消同一个按钮：只靠 type 区分蓝（预约）与红（取消），不额外上色 */
.book-card__act {
  flex-shrink: 0;
  font-weight: 500;
}
.book-card__detail {
  margin-left: auto;
  font-size: 12px;
  color: #b6c1d1;
  transition: color 0.16s ease;
}
.book-card:hover .book-card__detail {
  color: var(--el-color-primary);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 22px;
}

/* ============ 详情弹窗 ============ */
.dlg-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
}
.dlg-body {
  display: flex;
  gap: 24px;
  min-height: 260px;
}
.dlg-cover {
  flex-shrink: 0;
}
.dlg-info {
  flex: 1;
  min-width: 0;
}
.dlg-book-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.35;
  color: #16324f;
}
.dlg-author {
  margin: 8px 0 0;
  font-size: 14px;
  color: #6b7789;
}
.dlg-tags {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0 0;
}
.dlg-pub {
  font-size: 13px;
  color: #8a94a6;
}
/* 三个数字块：可借 / 馆藏 / 累计借阅 */
.dlg-stats {
  display: flex;
  gap: 10px;
  margin: 18px 0 0;
}
.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 10px 6px;
  border-radius: 8px;
  background: #f6f9fd;
  border: 1px solid #eaf0f8;
}
.stat-num {
  font-size: 19px;
  font-weight: 700;
  color: #2b6cb0;
  line-height: 1.2;
}
.stat-lbl {
  font-size: 11.5px;
  color: #8a94a6;
}
.dlg-meta {
  margin: 16px 0 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 18px;
}
.meta-row {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.meta-row dt {
  flex-shrink: 0;
  width: 62px;
  color: #9aa5b6;
}
.meta-row dd {
  margin: 0;
  min-width: 0;
  color: #33415c;
  /* 只在放不下时按词断，禁止逐字换行（ISBN 曾被挤成一行一个字） */
  overflow-wrap: anywhere;
}
.meta-loc {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #3d6ea8 !important;
}
.dlg-desc {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px dashed #e8eef6;
}
/* 2026-10-25：给「完整详情页」入口用的链接样式（该页此前无入口） */
.dlg-open-full {
  display: inline-block;
  margin-top: 12px;
  font-size: 13px;
  color: #2b6cb0;
  text-decoration: none;
}
.dlg-open-full:hover {
  text-decoration: underline;
}
.dlg-desc-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #1f2d3d;
}
.dlg-desc-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: #5c7189;
}
@media (max-width: 820px) {
  .dlg-body {
    flex-direction: column;
    align-items: center;
  }
  .dlg-meta {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .hot-list {
    flex-wrap: wrap;
  }
  .hot-card {
    flex: 1 1 45%;
    min-width: 0;
  }
}

@media (max-width: 640px) {
  .search-bar {
    flex-wrap: wrap;
  }
  .search-cat,
  .search-sort {
    width: 120px;
  }
  .hot-card {
    flex-basis: 100%;
  }
  .book-grid {
    grid-template-columns: 1fr;
  }
}
</style>
