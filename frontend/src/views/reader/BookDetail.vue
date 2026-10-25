<template>
  <!-- 图书详情页（独立页面形态：可分享 URL / 可收藏 / 可刷新 / 可打印）
       2026-10-25 改造：原页面只有一张描述表格、连封面都没有，观感上反而不如找书页的弹窗。
       现改为「弹窗的超集」——弹窗有的这里都有（封面 / 分类标签 / 三个数字卡 / 元信息 / 简介），
       另加弹窗形态给不了的同类图书推荐。 -->
  <div v-loading="loading">
    <div class="page-card">
      <div class="toolbar">
        <el-button @click="goBack">
          <el-icon><ArrowLeft /></el-icon>&nbsp;返回检索
        </el-button>
        <el-button
          v-if="book"
          :type="myReservation ? 'danger' : 'primary'"
          :loading="reserving"
          :data-testid="myReservation ? 'detail-page-cancel' : 'detail-page-reserve'"
          @click="toggleReserve"
          >{{ myReservation ? '取消预约' : '预约' }}</el-button
        >
      </div>

      <el-empty v-if="!loading && !book" description="图书不存在" />

      <template v-else-if="book">
        <div class="detail-hero">
          <div class="hero-cover">
            <BookCover :book="book" size="xl" />
          </div>
          <div class="hero-info">
            <h1 class="hero-title">{{ book.title }}</h1>
            <p class="hero-author">{{ book.author || '佚名' }}</p>

            <div class="hero-tags">
              <span class="tag-cat">{{ book.categoryName || '未分类' }}</span>
              <span class="hero-pub">{{ book.publisherName || '—' }}</span>
            </div>

            <div class="hero-stats">
              <div class="stat">
                <span class="stat-num">{{ availableStock(book) }}</span>
                <span class="stat-lbl">可借册数</span>
              </div>
              <div class="stat">
                <span class="stat-num">{{ book.totalStock }}</span>
                <span class="stat-lbl">馆藏总册数</span>
              </div>
              <div class="stat">
                <span class="stat-num">{{ book.borrowCount || 0 }}</span>
                <span class="stat-lbl">累计借阅</span>
              </div>
            </div>

            <dl class="hero-meta">
              <div class="meta-row">
                <dt>ISBN</dt>
                <dd>{{ book.isbn || '—' }}</dd>
              </div>
              <div class="meta-row">
                <dt>出版日期</dt>
                <dd>{{ book.publishDate || '—' }}</dd>
              </div>
              <div class="meta-row">
                <dt>定价</dt>
                <dd>{{ book.price == null ? '—' : '¥' + Number(book.price).toFixed(2) }}</dd>
              </div>
              <div class="meta-row">
                <dt>馆藏位置</dt>
                <dd>{{ book.location || '—' }}</dd>
              </div>
            </dl>
          </div>
        </div>

        <section class="detail-sec">
          <h3 class="sec-title">内容简介</h3>
          <p class="sec-text">{{ book.description || '暂无简介' }}</p>
        </section>

        <section v-if="sameCategory.length" class="detail-sec">
          <h3 class="sec-title">同类图书推荐</h3>
          <div class="same-list">
            <a
              v-for="b in sameCategory"
              :key="b.id"
              class="same-item"
              :href="'/book-detail/' + b.id"
              target="_blank"
              rel="noopener"
              data-testid="same-category-item"
            >
              <BookCover :book="b" size="sm" />
              <span class="same-title">{{ b.title }}</span>
              <span class="same-author">{{ b.author || '佚名' }}</span>
            </a>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getBook, listBooks } from '@/api/book'
import { reserveBook, cancelReservation, listMyReservations } from '@/api/reservation'
import { ElMessageBox } from 'element-plus'
import { msg } from '@/utils/message'
import BookCover from '@/components/BookCover.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const reserving = ref(false)
const book = ref(null)
// 当前这本书"我"的有效预约（排队中 / 待取书），null 表示没预约
const myReservation = ref(null)
// 同类图书（同分类，排除自身）——弹窗形态给不了的能力，2026-10-25 新增
const sameCategory = ref([])

/** 真实可借数 = 库存 - 待取预约数（待取不扣库存，故单独扣） */
function availableStock(row) {
  if (!row) return 0
  return Math.max(0, (row.stock || 0) - (row.reservedCount || 0))
}

function goBack() {
  router.back()
}

/** 同分类取若干本作为推荐；失败不影响主流程（保持静默，页面主体仍可用） */
async function loadSameCategory() {
  if (!book.value || !book.value.categoryId) {
    sameCategory.value = []
    return
  }
  try {
    const res = await listBooks({ page: 1, size: 6, categoryId: book.value.categoryId })
    const recs = (res.data && res.data.records) || []
    sameCategory.value = recs.filter((b) => b.id !== book.value.id).slice(0, 5)
  } catch (e) {
    sameCategory.value = []
  }
}

async function loadMyReservation() {
  if (!book.value) return
  try {
    const res = await listMyReservations({ page: 1, size: 50 })
    const recs = (res.data && res.data.records) || []
    myReservation.value =
      recs.find((r) => r.bookId === book.value.id && (r.status === 1 || r.status === 2)) || null
  } catch (e) {
    myReservation.value = null
  }
}

/** 预约 / 取消预约 同一个按钮：蓝=预约，红=取消；取消前二次确认 */
async function toggleReserve() {
  if (!book.value || reserving.value) return
  const mine = myReservation.value

  if (mine) {
    const tip =
      mine.status === 2
        ? '《' + book.value.title + '》已到馆待取，取消后需要重新排队，确定取消吗？'
        : '确定取消《' + book.value.title + '》的预约吗？取消后需要重新排队。'
    try {
      await ElMessageBox.confirm(tip, '取消预约', {
        type: 'warning',
        confirmButtonText: '确定取消',
        cancelButtonText: '再想想'
      })
    } catch (e) {
      return
    }
    reserving.value = true
    try {
      await cancelReservation(mine.id)
      msg.success('预约已取消')
      await loadMyReservation()
    } catch (e) {
      // 拦截器已提示
    } finally {
      reserving.value = false
    }
    return
  }

  reserving.value = true
  try {
    const res = await reserveBook({ bookId: book.value.id })
    msg.success(res.msg)
    const fresh = await getBook(book.value.id)
    book.value = fresh.data
    await loadMyReservation()
  } catch (e) {
    // 拦截器已提示
  } finally {
    reserving.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await getBook(route.params.id)
    book.value = res.data
    await loadMyReservation()
    await loadSameCategory()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.detail-hero {
  display: flex;
  gap: 24px;
  padding: 8px 4px 20px;
  border-bottom: 1px solid #ebeef5;
}
.hero-cover {
  flex: 0 0 auto;
}
.hero-info {
  flex: 1 1 auto;
  min-width: 0;
}
.hero-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  color: #1f2d3d;
  line-height: 1.3;
}
.hero-author {
  margin: 0 0 12px;
  font-size: 14px;
  color: #606266;
}
.hero-tags {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.tag-cat {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 12px;
  background: #ecf5ff;
  color: #2b6cb0;
  font-size: 12px;
}
.hero-pub {
  font-size: 13px;
  color: #909399;
}
.hero-stats {
  display: flex;
  gap: 28px;
  margin-bottom: 16px;
}
.stat {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.stat-num {
  font-size: 22px;
  font-weight: 600;
  color: #2b6cb0;
  line-height: 1.2;
}
.stat-lbl {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.hero-meta {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 32px;
}
.meta-row {
  display: flex;
  gap: 8px;
  font-size: 13px;
}
.meta-row dt {
  color: #909399;
  min-width: 62px;
}
.meta-row dd {
  margin: 0;
  color: #303133;
}
.detail-sec {
  padding: 20px 4px 4px;
}
.sec-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
  padding-left: 8px;
  border-left: 3px solid #2b6cb0;
}
.sec-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.9;
  color: #303133;
  white-space: pre-wrap;
}
.same-list {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}
.same-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 96px;
  text-decoration: none;
  color: inherit;
}
.same-item:hover .same-title {
  color: #2b6cb0;
}
.same-title {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.4;
  text-align: center;
  color: #303133;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.same-author {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}
</style>