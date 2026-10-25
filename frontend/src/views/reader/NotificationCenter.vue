<template>
  <!-- 消息中心：读者本人的站内通知（预约到书 / 到期 / 逾期 / 罚金 / 证件到期 / 欢迎） -->
  <div class="page-card" data-testid="notification-page">
    <div class="notify-head">
      <div class="card-title">消息中心</div>
      <div class="notify-head__right">
        <span class="notify-unread">未读 {{ unreadCount }}</span>
        <el-button
          link
          type="primary"
          :disabled="unreadCount === 0"
          data-testid="notification-read-all"
          @click="readAll"
          >全部已读</el-button
        >
      </div>
    </div>

    <el-tabs v-model="tab" class="notify-tabs" @tab-change="onTabChange">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="仅未读" name="unread" />
    </el-tabs>

    <div v-loading="loading" class="notify-list">
      <div
        v-for="n in list"
        :key="n.id"
        class="notify-item"
        :class="{ 'is-unread': n.readFlag === 0 }"
        data-testid="notification-item"
        @click="openItem(n)"
      >
        <span class="notify-dot" v-if="n.readFlag === 0"></span>
        <div class="notify-main">
          <div class="notify-title-row">
            <el-tag :type="tagType(n.type)" size="small" effect="light">{{ typeText(n.type) }}</el-tag>
            <span class="notify-title">{{ n.title }}</span>
          </div>
          <p class="notify-content">{{ n.content }}</p>
          <span class="notify-time">{{ n.createTime }}</span>
        </div>
      </div>
      <el-empty v-if="!loading && !list.length" description="暂无消息" />
    </div>

    <div v-if="total > 0" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listNotifications, markNotificationRead, markAllNotificationsRead } from '@/api/notification'
import { useNotificationStore } from '@/stores/notification'
import { useUserStore } from '@/stores/user'
import { msg } from '@/utils/message'
import {
  NOTIFICATION_TYPE_MAP,
  NOTIFICATION_TAG_MAP,
  NOTIFICATION_LINK_MAP
} from '@/utils/dict'

const router = useRouter()
const store = useNotificationStore()
const userStore = useUserStore()

const loading = ref(false)
const tab = ref('all')
const list = ref([])
const total = ref(0)
const unreadCount = ref(0)
const query = reactive({ page: 1, size: 10 })

const typeText = (t) => NOTIFICATION_TYPE_MAP[t] ?? '通知'
const tagType = (t) => NOTIFICATION_TAG_MAP[t] ?? 'info'

async function load() {
  loading.value = true
  try {
    const res = await listNotifications({
      page: query.page,
      size: query.size,
      unreadOnly: tab.value === 'unread'
    })
    const data = res.data || {}
    list.value = data.records || []
    total.value = Number(data.total || 0)
    unreadCount.value = Number(data.unreadCount || 0)
    store.setUnread(unreadCount.value)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  query.page = 1
  load()
}

function onSizeChange() {
  query.page = 1
  load()
}

async function openItem(n) {
  if (n.readFlag === 0) {
    try {
      await markNotificationRead(n.id)
      n.readFlag = 1
      unreadCount.value = Math.max(unreadCount.value - 1, 0)
      store.setUnread(unreadCount.value)
    } catch (e) {
      // 已读失败不阻断跳转
    }
  }
  const link = NOTIFICATION_LINK_MAP[n.type] || { path: '/my-borrow' }
  router.push(link)
}

async function readAll() {
  await markAllNotificationsRead()
  msg.success('已全部标记为已读')
  await load()
  userStore.refreshUnpaidFines && userStore.refreshUnpaidFines()
}

onMounted(load)
</script>

<style scoped>
.notify-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.notify-head__right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.notify-unread {
  font-size: 12.5px;
  color: #8a94a6;
}
.notify-list {
  min-height: 220px;
}
.notify-item {
  display: flex;
  gap: 10px;
  padding: 14px 12px;
  border-bottom: 1px solid #eef2f7;
  cursor: pointer;
  transition: background 0.15s;
}
.notify-item:hover {
  background: #f7fbff;
}
.notify-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: #f56c6c;
}
.notify-main {
  min-width: 0;
  flex: 1;
}
.notify-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.notify-title {
  font-size: 14px;
  font-weight: 600;
  color: #16324f;
}
.is-unread .notify-title {
  color: #0f2540;
}
.notify-content {
  margin: 6px 0 4px;
  font-size: 13px;
  line-height: 1.7;
  color: #5c7189;
}
.notify-time {
  font-size: 12px;
  color: #a4adb9;
}
.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
</style>
