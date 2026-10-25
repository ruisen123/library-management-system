<template>
  <!-- 图书封面：三层降级 —— ①cover 字段有值用真图；②按 ISBN 找 public/covers/<isbn>.jpg；③都没有或加载失败则按书名哈希生成渐变书脊 -->
  <div class="book-cover" :class="'book-cover--' + size" :style="fallbackStyle">
    <img
      v-if="useImg"
      :src="src"
      :alt="(book && book.title) || '图书封面'"
      class="book-cover__img"
      loading="lazy"
      @error="onImgError"
    />
    <div v-else class="book-cover__fallback">
      <span class="book-cover__title">{{ displayTitle }}</span>
      <span v-if="book && book.author" class="book-cover__author">{{ book.author }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  // 图书对象：至少含 { isbn, title, author }，可选 cover
  book: { type: Object, default: () => ({}) },
  // 尺寸档位：sm=40×54（表格行内）/ md=64×86（列表）/ lg=116×155（卡片/详情）
  size: { type: String, default: 'md' }
})

const emit = defineEmits(['load-error'])

const imgFailed = ref(false)

/** 图片地址：cover 字段优先（支持外部 URL），否则按 ISBN 取本地资源 */
const src = computed(() => {
  const b = props.book || {}
  if (b.cover) return b.cover
  if (b.isbn) return '/covers/' + b.isbn + '.jpg'
  return ''
})

const useImg = computed(() => !!src.value && !imgFailed.value)

// 换书时重置失败标记（列表复用组件实例时不会重新挂载）
// 地址或 ISBN 任一变化都要重置失败标记（2026-10-24 修既有缺陷）：
// 原先只监听 isbn，导致"先填错地址、再改成对的"会一直停在降级封面不重试加载
watch(
  () => [(props.book && props.book.isbn) || '', (props.book && props.book.cover) || ''],
  () => {
    imgFailed.value = false
  }
)

function onImgError() {
  imgFailed.value = true
  // 对外通报"该地址加载不了、已回退生成封面"，供管理端提示管理员（2026-10-24）
  emit('load-error')
}

/** 书名转色相：同一本书永远同一颜色，且不同书分散在色环上 */
function hueOf(text) {
  let h = 0
  for (let i = 0; i < text.length; i++) {
    h = (h * 31 + text.charCodeAt(i)) >>> 0
  }
  return h % 360
}

const fallbackStyle = computed(() => {
  const title = (props.book && props.book.title) || ''
  const hue = hueOf(title)
  const c1 = 'hsl(' + hue + ', 56%, 48%)'
  const c2 = 'hsl(' + ((hue + 38) % 360) + ', 62%, 33%)'
  return { background: 'linear-gradient(150deg, ' + c1 + ', ' + c2 + ')' }
})

/** 无图时显示的书名：超过 12 字截断，避免撑破书脊 */
const displayTitle = computed(() => {
  const t = (props.book && props.book.title) || '未知'
  return t.length > 12 ? t.slice(0, 12) + '…' : t
})
</script>

<style scoped>
.book-cover {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 4px;
  box-shadow:
    0 1px 3px rgba(16, 24, 40, 0.18),
    0 1px 2px rgba(16, 24, 40, 0.1);
  flex-shrink: 0;
  /* 固定 3:4 书形，配合 object-fit 裁切，比例不一的封面也不会变形 */
  aspect-ratio: 3 / 4;
  transition: transform 0.18s ease;
}
.book-cover:hover {
  transform: translateY(-2px);
}

.book-cover--sm {
  width: 40px;
}
.book-cover--md {
  width: 64px;
}
.book-cover--lg {
  width: 116px;
}
/* 详情弹窗用：封面要放大才看得清 */
/* 后台「编辑图书」的封面预览用：比 xl 再大一档，尽量接近纸质封面的观感（2026-10-25） */
.book-cover--xxl {
  width: 220px;
  border-radius: 8px;
  box-shadow:
    0 10px 26px rgba(16, 24, 40, 0.2),
    0 3px 8px rgba(16, 24, 40, 0.12);
}
.book-cover--xl {
  width: 176px;
  border-radius: 6px;
  box-shadow:
    0 6px 18px rgba(16, 24, 40, 0.18),
    0 2px 6px rgba(16, 24, 40, 0.1);
}

.book-cover__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.book-cover__fallback {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 4px;
  text-align: center;
  color: #fff;
}

.book-cover__title {
  font-size: 11px;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: 0.5px;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.book-cover__author {
  font-size: 9px;
  opacity: 0.82;
  line-height: 1.2;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.book-cover--sm .book-cover__title {
  font-size: 8px;
  -webkit-line-clamp: 2;
}
.book-cover--sm .book-cover__author {
  display: none;
}
.book-cover--lg .book-cover__title {
  font-size: 14px;
}
.book-cover--lg .book-cover__author {
  font-size: 11px;
}
.book-cover--xl .book-cover__title {
  font-size: 18px;
  -webkit-line-clamp: 4;
}
.book-cover--xl .book-cover__author {
  font-size: 12px;
}
</style>
