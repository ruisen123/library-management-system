<template>
  <!-- 图书助理：纯前端意图识别 + 调现有接口，不新增后端接口、不占路由页面 -->
  <div class="assistant-root">
    <button class="assistant-fab" data-testid="assistant-fab" :title="'图书助理'" @click="open = true">
      <el-icon :size="22"><ChatDotRound /></el-icon>
    </button>

    <el-drawer
      v-model="open"
      title="图书助理"
      direction="rtl"
      size="380px"
      :append-to-body="true"
      data-testid="assistant-drawer"
    >
      <div class="chat">
        <div class="chat-list" ref="listRef">
          <div v-for="(m, i) in messages" :key="i" class="msg" :class="'msg--' + m.role">
            <div class="msg-bubble" v-html="m.html"></div>
          </div>
          <div v-if="thinking" class="msg msg--bot">
            <div class="msg-bubble msg-thinking">正在查…</div>
          </div>
        </div>

        <div v-if="messages.length <= 1" class="quick-ask">
          <button v-for="q in quickQuestions" :key="q" class="quick-chip" @click="ask(q)">
            {{ q }}
          </button>
        </div>

        <div class="chat-input">
          <el-input
            v-model="draft"
            placeholder="问我：有没有《算法导论》/ 我借了几本 / 欠多少罚金"
            :disabled="thinking"
            data-testid="assistant-input"
            @keyup.enter="ask()"
          />
          <el-button type="primary" :disabled="thinking || !draft.trim()" data-testid="assistant-send" @click="ask()"
            >发送</el-button
          >
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, nextTick, computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { listBooks } from '@/api/book'
import { listMyBorrows, listBorrowRecords } from '@/api/borrow'
import { listFines } from '@/api/fine'
import { getRuleConfig } from '@/api/rule'

const userStore = useUserStore()
const open = ref(false)
const thinking = ref(false)
const draft = ref('')
const listRef = ref(null)

const role = computed(() => userStore.role || '')
const isReader = computed(() => role.value === 'READER')

const messages = ref([
  {
    role: 'bot',
    html: '你好，我是图书助理。<br>可以问我：<b>某本书有没有</b>、<b>我借了几本</b>、<b>欠多少罚金</b>、<b>借阅规则</b>。'
  }
])

const quickQuestions = computed(() =>
  isReader.value
    ? ['有没有《算法导论》', '我借了几本', '我有没有罚金', '借阅规则是什么']
    : ['有没有《算法导论》', '当前在借多少本', '有多少未缴罚金', '借阅规则是什么']
)

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
}

function pushBot(html) {
  messages.value.push({ role: 'bot', html })
  scrollBottom()
}

function pushUser(text) {
  messages.value.push({ role: 'user', html: escapeHtml(text) })
  scrollBottom()
}

function scrollBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// —— 意图识别（关键词 + 正则，纯前端） ——
const RULES = [
  {
    test: /规则|借几本|借多久|借期|续借|罚金标准|多少钱一天|上限/,
    run: async () => {
      // 规则数值一律取后端，不在前端抄第二份常量；读者级额度/欠费上限以个人档案为准
      let cfg = null
      try {
        const res = await getRuleConfig()
        cfg = res.data || null
      } catch (e) {
        // 接口失败时只讲通用口径，不编造阈值
      }
      const maxBorrow = cfg && cfg.maxBorrow != null ? cfg.maxBorrow : 5
      const borrowDays = cfg && cfg.borrowDays != null ? cfg.borrowDays : 30
      const renewDays = cfg && cfg.renewDays != null ? cfg.renewDays : 30
      const finePerDay = cfg && cfg.finePerDay != null ? cfg.finePerDay : 0.5
      return (
        '本馆借阅规则：<br>· 新读者默认可借 <b>' + maxBorrow + ' 册</b>（个人额度以读者档案为准）<br>' +
        '· 借期 <b>' + borrowDays + ' 天</b><br>' +
        '· 每册限续借 <b>1 次</b>，续借延长 ' + renewDays + ' 天<br>' +
        '· 逾期按 <b>' + finePerDay + ' 元/天</b>计罚金<br>' +
        '· 欠费是否影响借阅，按个人「未缴上限」判断，可在「我的书架 → 罚金」查看'
      )
    }
  },
  {
    test: /罚金|罚款|欠费|未缴/,
    run: async () => {
      const res = await listFines({ page: 1, size: 100, status: 1 })
      const total = Number((res.data && res.data.total) || 0)
      if (!total) return '目前没有未缴罚金，保持得不错。'
      const records = (res.data && res.data.records) || []
      const sum = records.reduce((s, r) => s + Number(r.amount || 0), 0).toFixed(2)
      const sumText = total > records.length
        ? '前 ' + records.length + ' 笔合计 <b>¥' + sum + '</b>（共 ' + total + ' 笔）'
        : '合计 <b>¥' + sum + '</b>'
      return (
        (isReader.value ? '你有 ' : '当前共有 ') +
        '<b>' + total + '</b> 笔未缴罚金，' + sumText + '。' +
        (isReader.value ? '<br>罚金由馆员收款后标记已缴，可到「我的书架 → 罚金」查看明细。' : '<br>可在「罚金管理」里办理收款。')
      )
    }
  },
  {
    test: /到期|逾期|该还|什么时候还/,
    run: async () => {
      const res = isReader.value
        ? await listMyBorrows({ page: 1, size: 100, status: 1 })
        : await listBorrowRecords({ page: 1, size: 100, status: 1 })
      const recs = (res.data && res.data.records) || []
      if (!recs.length) return isReader.value ? '你当前没有在借图书。' : '当前没有在借记录。'
      const risky = recs
        .map((r) => ({ ...r, days: remainDays(r.dueDate) }))
        .filter((r) => r.days <= 3)
        .sort((a, b) => a.days - b.days)
      if (!risky.length) return '最近 3 天内没有到期的书，不用着急。'
      const lines = risky.slice(0, 5).map((r) => {
        const tag = r.days < 0 ? '<b>' + -r.days + ' 天前已逾期</b>' : r.days === 0 ? '<b>今天到期</b>' : r.days + ' 天后到期'
        return '· 《' + escapeHtml(r.bookTitle) + '》' + tag
      })
      return '需要留意：<br>' + lines.join('<br>')
    }
  },
  {
    test: /推荐|热门|借得多|好看/,
    run: async () => {
      // 用后端白名单排序取热门（原实现是自己拉 50 条再按 borrowCount 排序，
      // 馆藏超过 50 本时会漏掉真正的热门书；2026-10-25 R3 复查改用 sort=popular）
      const res = await listBooks({ page: 1, size: 10, sort: 'popular' })
      const recs = (res.data && res.data.records) || []
      const top = recs
        .slice()
        .sort((a, b) => Number(b.borrowCount || 0) - Number(a.borrowCount || 0))
        .slice(0, 3)
      if (!top.length) return '暂时没有可推荐的书。'
      const lines = top.map((b, i) => (i + 1) + '. 《' + escapeHtml(b.title) + '》 借过 ' + (b.borrowCount || 0) + ' 次')
      return '馆里最受欢迎的书：<br>' + lines.join('<br>')
    }
  },
  {
    test: /借了几本|我的借阅|在借|借了哪些/,
    run: async () => {
      const res = isReader.value
        ? await listMyBorrows({ page: 1, size: 100, status: 1 })
        : await listBorrowRecords({ page: 1, size: 100, status: 1 })
      const total = Number((res.data && res.data.total) || 0)
      const recs = (res.data && res.data.records) || []
      if (!total) return isReader.value ? '你当前没有在借图书。' : '当前没有在借记录。'
      const lines = recs.slice(0, 5).map((r) => '· 《' + escapeHtml(r.bookTitle) + '》 应还 ' + r.dueDate)
      return (
        (isReader.value ? '你当前在借 <b>' + total + '</b> 本：' : '当前共有 <b>' + total + '</b> 条在借记录，前 5 条：') +
        '<br>' + lines.join('<br>')
      )
    }
  }
]

/** 本地 'YYYY-MM-DD'（避免 toISOString 的 UTC 偏移，与「我的书架」口径一致） */
function todayStr() {
  const t = new Date()
  const m = String(t.getMonth() + 1).padStart(2, '0')
  const d = String(t.getDate()).padStart(2, '0')
  return t.getFullYear() + '-' + m + '-' + d
}

/** 剩余天数：<0 表示已逾期；后端 due_date < today 才算逾期（当天不算） */
function remainDays(dueDate) {
  return Math.floor((new Date(dueDate) - new Date(todayStr())) / 86400000)
}

/** 从自然语言里剥出真正的书名/作者词：去掉书名号与"有没有/帮我找"这类问句壳 */
function extractKeyword(q) {
  return q
    .replace(/[《》「」【】"'"'']/g, '')
    .replace(/有没有|有吗|有没有卖|请问|帮我找一下|帮我找|帮我|我想找|我想看|想找|想借|查找|查一下|搜一下|找一下|这本书|那本书|在哪里|在哪个位置|能借吗|可以借吗/g, '')
    .replace(/[?？。！!，,]/g, '')
    .trim()
}

/** 兜底：当书名/ISBN 检索 */
async function searchBook(q) {
  const kw = extractKeyword(q) || q
  const res = await listBooks({ page: 1, size: 5, keyword: kw })
  const recs = (res.data && res.data.records) || []
  if (!recs.length) return '没找到匹配「' + escapeHtml(kw) + '」的图书，换个书名或作者试试。'
  const lines = recs.map((b) => {
    // 与读者端同口径：待取预约占用副本，真实可借数 = stock - reservedCount
    const stock = Math.max(0, Number(b.stock || 0) - Number(b.reservedCount || 0))
    const state = stock > 0 ? '<b>可借 ' + stock + ' 本</b>' : '<span style="color:#c05621">已全部借出</span>'
    return '· 《' + escapeHtml(b.title) + '》' + state + (b.location ? '，在 ' + escapeHtml(b.location) : '')
  })
  return '找到 ' + recs.length + ' 本：<br>' + lines.join('<br>')
}

async function ask(text) {
  const q = String(text != null ? text : draft.value).trim()
  if (!q || thinking.value) return
  draft.value = ''
  pushUser(q)
  thinking.value = true
  try {
    let html = null
    for (const rule of RULES) {
      if (rule.test.test(q)) {
        html = await rule.run()
        break
      }
    }
    if (html == null) html = await searchBook(q)
    pushBot(html)
  } catch (e) {
    pushBot('查询时出了点问题，稍后再试一次。')
  } finally {
    thinking.value = false
  }
}
</script>

<style scoped>
/* 浮动按钮停在内容区左下角（原为右下角）。
   .pager 全局 flex-end → 各列表页的分页器都落在视口右下，右下角按钮会压住页码和「下一页」
   （1440×900 找书页实测：压页码 200px²、压下一页 534px²）。
   左下后实测 4 个页面 × 5 档视口（宽 1024~1920）共 20 组，与任何可点击控件零重叠。
   left 跟随 --libms-aside-w（展开 240px / 折叠 64px）以避开侧边栏。 */
.assistant-fab {
  position: fixed;
  left: calc(var(--libms-aside-w, 240px) + 24px);
  right: auto;
  bottom: 24px;
  z-index: 2000;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  color: #fff;
  background: linear-gradient(140deg, #4a90d9, #2b6cb0);
  box-shadow: 0 6px 18px rgba(43, 108, 176, 0.36);
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease;
}
.assistant-fab:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px rgba(43, 108, 176, 0.44);
}
/* 对话区 */
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 2px 8px;
}
.msg {
  display: flex;
  margin-bottom: 10px;
}
.msg--bot {
  justify-content: flex-start;
}
.msg--user {
  justify-content: flex-end;
}
.msg-bubble {
  max-width: 88%;
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.65;
  word-break: break-word;
}
.msg--bot .msg-bubble {
  background: #f2f7fd;
  color: #33415c;
  border-top-left-radius: 3px;
}
.msg--user .msg-bubble {
  background: #2b6cb0;
  color: #fff;
  border-top-right-radius: 3px;
}
.msg-thinking {
  color: #8a94a6 !important;
  background: #f7f9fc !important;
}

.quick-ask {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 0 10px;
}
.quick-chip {
  padding: 5px 11px;
  border-radius: 14px;
  font-size: 12px;
  color: #2b6cb0;
  background: #f0f7ff;
  border: 1px solid #d8e8fa;
  cursor: pointer;
  transition: background 0.16s ease;
}
.quick-chip:hover {
  background: #e4f0fd;
}

.chat-input {
  display: flex;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid #eef2f7;
}
</style>
