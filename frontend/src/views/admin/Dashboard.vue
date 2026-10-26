<template>
  <!-- 数据总览：统计卡片 + 借阅趋势 + 分类占比 + 热度榜 -->
  <div v-loading="loading" class="dashboard-page">
    <!-- 统计卡片 -->
    <div class="stat-row">
      <div v-for="s in statCards" :key="s.label" class="stat-card">
        <div class="stat-icon" :style="{ background: s.bg, color: s.color }">
          <el-icon><component :is="s.icon" /></el-icon>
        </div>
        <div>
          <div class="stat-num">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </div>
      </div>
    </div>

    <!-- 报表导出：汇总 / 借阅明细 / 罚金明细 / 热门图书 -->
    <div class="page-card report-card">
      <div class="card-title report-title">
        <span>借阅报表导出</span>
        <span class="report-actions">
          <el-date-picker
            v-model="exportRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :shortcuts="exportShortcuts"
            size="small"
            style="width: 250px"
          />
          <el-button
            type="primary"
            size="small"
            :loading="exporting"
            data-testid="stats-export"
            @click="doExport"
            >导出 Excel</el-button
          >
        </span>
      </div>
      <div class="report-hint">
        导出汇总、借阅明细、罚金明细、热门图书；不选日期默认近 30 天，单次最多 20000 行。
      </div>
    </div>

    <!-- 数据健康诊断（只读巡检：检测并定位账实不符，不修改业务数据） -->
    <div class="page-card" style="margin-bottom: 16px">
      <div class="card-title audit-title">
        <span>数据健康诊断</span>
        <span class="audit-actions">
          <el-tag
            v-if="audit"
            :type="audit.anomalyItemCount ? 'danger' : 'success'"
            size="small"
            style="margin-right: 8px"
          >
            {{ audit.anomalyItemCount ? '发现 ' + audit.anomalyItemCount + ' 项异常' : '全部通过' }}
          </el-tag>
          <el-button type="primary" size="small" :loading="auditing" @click="doAudit">
            开始巡检
          </el-button>
          <el-button size="small" :loading="scanning" @click="doScanNotifications">
            补扫通知
          </el-button>
        </span>
      </div>

      <div v-if="!audit" class="audit-hint">
        按既定规则检查存量数据的一致性（库存、在借数、借阅状态、罚金配套、删除引用），
        检测并定位问题记录。只读诊断，不会修改任何业务数据。
      </div>

      <template v-else>
        <div class="audit-meta">
          快照时点 {{ audit.snapshotAt }} ｜ 规则 {{ audit.ruleCount }} 条 / 检查项
          {{ audit.checkCount }} 项 ｜ 通过 {{ audit.passedCount }} 项 ｜ 异常
          {{ audit.anomalyItemCount }} 项（高 {{ audit.highCount }} / 中
          {{ audit.mediumCount }} / 低 {{ audit.lowCount }}）
        </div>
        <el-alert
          :type="audit.anomalyItemCount ? 'warning' : 'success'"
          :closable="false"
          show-icon
          :title="audit.conclusion"
          style="margin: 10px 0"
        />
        <el-table :data="audit.items" size="small" stripe max-height="320">
          <el-table-column prop="ruleId" label="编号" width="80" />
          <el-table-column prop="name" label="检查项" min-width="170" />
          <el-table-column prop="level" label="层级" width="70" align="center" />
          <el-table-column label="结果" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.passed ? 'success' : 'danger'" size="small">
                {{ row.passed ? '通过' : '异常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="count" label="异常行数" width="90" align="right" />
          <el-table-column label="定位 / 期望值" min-width="260">
            <template #default="{ row }">
              <span v-if="row.passed" class="audit-expect">{{ row.expected }}</span>
              <span v-else class="audit-hit">
                {{ row.samples && row.samples.length ? row.samples.join('，') : row.expected }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>

    <!-- 图表区 -->
    <div class="chart-flex" style="margin-bottom: 16px">
      <div class="page-card" style="margin-bottom: 0">
        <div class="card-title">月度借阅量趋势（近 12 个月）</div>
        <div ref="trendRef" style="height: 300px"></div>
      </div>
      <div class="page-card" style="margin-bottom: 0">
        <div class="card-title">分类借阅占比</div>
        <div ref="pieRef" style="height: 300px"></div>
      </div>
    </div>

    <!-- 热度榜 -->
    <div class="page-card" style="margin-bottom: 0">
      <div class="card-title">借阅热度 TOP10 图书</div>
      <el-table :data="topBooks" stripe>
        <el-table-column type="index" label="排名" width="70" align="center" />
        <el-table-column prop="title" label="书名" min-width="240" />
        <el-table-column prop="author" label="作者" width="160" />
        <el-table-column prop="categoryName" label="分类" width="110" />
        <el-table-column prop="borrowCount" label="累计借阅次数" width="130" sortable />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
// ECharts 按需引入（docs/02 迭代五要求），显著减小首屏包体
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getOverview, getTrend, getPie, getTop, exportReport } from '@/api/stats'
import { runAudit } from '@/api/audit'
import { scanNotifications } from '@/api/notification'
import { msg } from '@/utils/message'
import { saveBlobResponse } from '@/utils/download'

echarts.use([LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const loading = ref(false)
const overview = reactive({
  bookKinds: 0,
  totalStock: 0,
  onBorrow: 0,
  readerCount: 0,
  todayBorrow: 0,
  todayReturn: 0
})
// 统计数字的"显示值"：加载完成后从 0 滚到目标值（600ms，一次性，不循环）
const shown = reactive({
  bookKinds: 0,
  totalStock: 0,
  onBorrow: 0,
  readerCount: 0,
  todayBorrow: 0,
  todayReturn: 0
})

function animateNumbers(target) {
  const keys = Object.keys(shown)
  const start = {}
  keys.forEach((k) => (start[k] = shown[k]))
  const t0 = performance.now()
  const dur = 600
  function step(now) {
    const p = Math.min(1, (now - t0) / dur)
    const e = 1 - Math.pow(1 - p, 3)
    keys.forEach((k) => {
      shown[k] = Math.round(start[k] + (Number(target[k] || 0) - start[k]) * e)
    })
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

const topBooks = ref([])
const trendRef = ref(null)
const pieRef = ref(null)
let trendChart = null
let pieChart = null

// 数据健康诊断：报告对象（null 表示尚未巡检）与执行中标记
const audit = ref(null)
const auditing = ref(false)

// —— 报表导出 ——
const exportRange = ref([])
const exporting = ref(false)
const exportShortcuts = [
  {
    text: '近 30 天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(end.getDate() - 29)
      return [start, end]
    }
  },
  {
    text: '本月',
    value: () => {
      const now = new Date()
      return [new Date(now.getFullYear(), now.getMonth(), 1), now]
    }
  },
  {
    text: '上月',
    value: () => {
      const now = new Date()
      return [new Date(now.getFullYear(), now.getMonth() - 1, 1), new Date(now.getFullYear(), now.getMonth(), 0)]
    }
  }
]

async function doExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const params = {}
    if (exportRange.value && exportRange.value.length === 2) {
      params.startDate = exportRange.value[0]
      params.endDate = exportRange.value[1]
    }
    const res = await exportReport(params)
    saveBlobResponse(res, 'LibMS_报表.xlsx')
    msg.success('报表已开始下载')
  } catch (e) {
    msg.error((e && e.message) || '导出失败，请重试')
  } finally {
    exporting.value = false
  }
}

const scanning = ref(false)

/** 手动补扫通知：仅系统管理员可见（dashboard 路由本身即 ADMIN 专属）；成功与否只报状态，不假设返回结构 */
async function doScanNotifications() {
  scanning.value = true
  try {
    await scanNotifications()
    msg.success('补扫完成')
  } catch (e) {
    // 拦截器已提示
  } finally {
    scanning.value = false
  }
}

/** 执行一次只读巡检：规则由后端固定定义，前端不传任何参数 */
async function doAudit() {
  auditing.value = true
  try {
    const res = await runAudit()
    audit.value = res.data
  } catch (e) {
    // 拦截器已提示
  } finally {
    auditing.value = false
  }
}

// 统计卡片（值来自 overview，样式色按设计规范）
const statCards = computed(() => [
  {
    label: '图书品种',
    value: shown.bookKinds,
    icon: 'Collection',
    bg: '#EBF3FB',
    color: '#2B6CB0'
  },
  {
    label: '馆藏总册数',
    value: shown.totalStock,
    icon: 'Files',
    bg: '#E6F4F0',
    color: '#2C7A7B'
  },
  { label: '当前在借', value: shown.onBorrow, icon: 'Tickets', bg: '#FBEEE3', color: '#C05621' },
  {
    label: '注册读者',
    value: shown.readerCount,
    icon: 'UserFilled',
    bg: '#EAF5E6',
    color: '#529B4E'
  },
  {
    label: '今日借/还（次）',
    value: shown.todayBorrow + ' / ' + shown.todayReturn,
    icon: 'Calendar',
    bg: '#EDEAF6',
    color: '#7C5CBF'
  }
])

async function loadData() {
  loading.value = true
  try {
    const [ov, tr, pie, top] = await Promise.all([getOverview(), getTrend(), getPie(), getTop()])
    Object.assign(overview, ov.data)
    animateNumbers(ov.data)
    topBooks.value = top.data
    await nextTick()
    renderTrend(tr.data)
    renderPie(pie.data)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function renderTrend(data) {
  trendChart?.dispose()
  trendChart = echarts.init(trendRef.value)
  trendChart.setOption({
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: data.months, axisLine: { lineStyle: { color: '#E4E7ED' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#F5F7FA' } } },
    tooltip: { trigger: 'axis' },
    series: [
      {
        type: 'line',
        data: data.values,
        smooth: true,
        itemStyle: { color: '#2B6CB0' },
        lineStyle: { width: 3, color: '#2B6CB0' },
        areaStyle: { color: 'rgba(43,108,176,0.10)' }
      }
    ]
  })
}

function renderPie(data) {
  pieChart?.dispose()
  pieChart = echarts.init(pieRef.value)
  pieChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, icon: 'circle', itemWidth: 8 },
    color: ['#2B6CB0', '#2C7A7B', '#C05621', '#67C23A', '#E6A23C'],
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '45%'],
        label: { formatter: '{b}\n{d}%' },
        data
      }
    ]
  })
}

/** 窗口缩放时自适应图表尺寸 */
function handleResize() {
  trendChart && trendChart.resize()
  pieChart && pieChart.resize()
}

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

// 离开页面时销毁图表实例，释放内存，避免重复初始化报错
onUnmounted(() => {
  trendChart && trendChart.dispose()
  pieChart && pieChart.dispose()
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
/* 统计卡片与图表布局类已在全局样式定义，此处仅补充"数据健康诊断"卡片样式 */
/* 底部留白：助理浮动按钮固定在内容区左下（距视口底 24px、高 52px），
   滚到底时避免盖住最后的卡片/表格（实测原遮挡 1926~1939 px²）。 */
.dashboard-page {
  padding-bottom: 96px;
}
.audit-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.audit-actions {
  display: flex;
  align-items: center;
}
.audit-hint {
  color: #7a8699;
  font-size: 13px;
  line-height: 1.9;
}
.audit-meta {
  color: #5a6675;
  font-size: 13px;
}
.audit-expect {
  color: #8a97a6;
  font-size: 12.5px;
}
.audit-hit {
  color: #c05621;
  font-weight: 600;
  font-size: 12.5px;
}

/* 报表导出条 */
.report-card {
  margin-bottom: 16px;
}
.report-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.report-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.report-hint {
  color: #7a8699;
  font-size: 12.5px;
  line-height: 1.8;
}
</style>
