<template>
  <!-- 图书档案管理：图书列表 + 新增/编辑 + 删除 -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">图书档案管理</div>

    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="书名 / 作者 / ISBN"
        clearable
        @keyup.enter="loadList"
      >
        <template #prefix
          ><el-icon><Search /></el-icon
        ></template>
      </el-input>
      <el-select v-model="query.categoryId" placeholder="分类" clearable>
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-checkbox v-model="query.onlyStock">仅看有库存</el-checkbox>
      <el-button type="primary" @click="loadList">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <div style="flex: 1"></div>
      <el-button data-testid="book-import-template" @click="downloadTemplate">下载导入模板</el-button>
      <el-button data-testid="book-import-open" @click="openImport">批量导入</el-button>
      <el-button type="primary" @click="openDialog(null)">
        <el-icon><Plus /></el-icon>&nbsp;新增图书
      </el-button>
    </div>

    <el-table :data="list" stripe>
      <!-- 2026-10-24 补齐：管理员/馆员端此前完全看不到封面（BookCover 仅读者端使用），
           而 book.cover 字段后端早已读写（BookServiceImpl.java:190/:268），此处补列表缩略图 -->
      <el-table-column label="封面" width="76" align="center">
        <template #default="{ row }">
          <BookCover :book="row" size="sm" />
        </template>
      </el-table-column>
      <el-table-column prop="title" label="书名" min-width="220" show-overflow-tooltip />
      <el-table-column prop="isbn" label="ISBN" width="140" />
      <el-table-column prop="author" label="作者" width="140" show-overflow-tooltip />
      <el-table-column prop="categoryName" label="分类" width="110" />
      <el-table-column prop="publisherName" label="出版社" width="160" show-overflow-tooltip />
      <el-table-column label="定价" width="90" align="right">
        <template #default="{ row }">{{
          row.price == null ? '—' : '¥' + Number(row.price).toFixed(2)
        }}</template>
      </el-table-column>
      <el-table-column prop="totalStock" label="馆藏" width="70" align="center" />
      <el-table-column label="可借" width="90" align="center">
        <template #default="{ row }">
          <!-- 与读者端同口径：待取预约占用副本，真实可借数 = stock - reservedCount -->
          <el-tag v-if="availableStock(row) <= 0" type="danger" size="small">无库存</el-tag>
          <el-tag v-else-if="availableStock(row) <= 2" type="warning" size="small">{{
            availableStock(row)
          }}</el-tag>
          <el-tag v-else type="success" size="small">{{ availableStock(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="borrowCount" label="累计借阅" width="90" align="center" />
      <el-table-column prop="location" label="馆藏位置" width="110" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="removeBook(row)">删除</el-button>
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

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.form.id ? '编辑图书' : '新增图书'"
      width="920px"
    >
      <el-form :model="dialog.form" label-width="90px">
        <el-form-item label="ISBN">
          <el-input v-model="dialog.form.isbn" placeholder="如 9787111544937" />
        </el-form-item>
        <el-form-item label="书名" required>
          <el-input v-model="dialog.form.title" />
        </el-form-item>
        <el-form-item label="作者">
          <el-input v-model="dialog.form.author" />
        </el-form-item>
        <el-form-item label="分类" required>
          <el-select v-model="dialog.form.categoryId" placeholder="请选择分类" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="出版社" required>
          <el-select
            v-model="dialog.form.publisherId"
            placeholder="请选择出版社"
            style="width: 100%"
          >
            <el-option v-for="p in publishers" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="出版日期">
          <el-date-picker
            v-model="dialog.form.publishDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="定价">
          <el-input-number
            v-model="dialog.form.price"
            :precision="2"
            :min="0"
            :step="1"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="总册数">
          <el-input-number
            v-model="dialog.form.totalStock"
            :min="0"
            :step="1"
            style="width: 200px"
            @change="onTotalStockChange"
          />
        </el-form-item>
        <el-form-item label="可借数">
          <el-input-number v-model="dialog.form.stock" :min="0" :step="1" style="width: 200px" />
        </el-form-item>
        <el-form-item label="馆藏位置">
          <el-input v-model="dialog.form.location" placeholder="如 A区-3排-01" />
        </el-form-item>
        <el-form-item label="封面">
          <div class="cover-editor">
            <div class="cover-preview">
              <BookCover :book="dialog.form" size="xxl" @load-error="coverBroken = true" />
            </div>
            <div class="cover-side">
              <!-- 2026-10-25：由「只能填地址」改为可直接从电脑选图上传（POST /books/cover），
                   上传成功后把返回的 /upload/xxx.jpg 写回 cover 字段，预览立即刷新 -->
              <el-upload
                :auto-upload="false"
                :show-file-list="false"
                accept=".jpg,.jpeg,.png,.webp,.gif"
                :on-change="onCoverFileChange"
              >
                <el-button type="primary" :loading="coverUploading" data-testid="cover-upload-btn">
                  <el-icon><UploadFilled /></el-icon>&nbsp;从电脑选择图片
                </el-button>
              </el-upload>
              <p class="cover-tip">支持 jpg / jpeg / png / webp / gif，单张不超过 2MB</p>
              <el-input
                v-model="dialog.form.cover"
                placeholder="或填写图片地址：/upload/xxx.jpg 或外链 https://..."
                clearable
                class="cover-url"
                @input="coverBroken = false"
              />
              <p v-if="coverBroken" class="cover-tip cover-tip--warn">
                该地址加载不出来，已回退为系统生成的封面，请检查图片地址或重新上传
              </p>
              <p class="cover-tip">
                留空则按 ISBN 取本地封面（public/covers/&lt;isbn&gt;.jpg），仍无则由系统按书名生成渐变封面
              </p>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="简介">
          <el-input
            v-model="dialog.form.description"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入弹窗：下载模板 → 选择文件 → 逐行校验并返回失败明细 -->
    <el-dialog
      v-model="importDialog.visible"
      title="批量导入图书"
      width="640px"
      data-testid="book-import-dialog"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="请使用系统模板填写：分类/出版社须与系统已有名称一致；新书可借册数=总册数。"
      />
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".xlsx,.xls"
        :file-list="importDialog.fileList"
        :on-change="onImportFileChange"
        :on-remove="onImportFileRemove"
        :on-exceed="onImportExceed"
        style="margin-top: 14px"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将 Excel 拖到此处，或 <em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持 .xlsx / .xls，单次最多 5000 行、5MB</div>
        </template>
      </el-upload>
      <div v-if="importDialog.fileName" class="import-file-name">
        已选择：{{ importDialog.fileName }}
      </div>

      <div v-if="importDialog.result" class="import-result" data-testid="book-import-result">
        <el-alert
          :type="importDialog.result.failed ? 'warning' : 'success'"
          :closable="false"
          show-icon
          :title="`成功 ${importDialog.result.success} 本，失败 ${importDialog.result.failed} 行`"
        />
        <el-table
          v-if="importDialog.result.errors && importDialog.result.errors.length"
          :data="importDialog.result.errors.map((m, i) => ({ idx: i + 1, msg: m }))"
          size="small"
          max-height="220"
          style="margin-top: 10px"
        >
          <el-table-column prop="idx" label="#" width="50" />
          <el-table-column prop="msg" label="失败原因" />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="importDialog.visible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="importDialog.uploading"
          :disabled="!importDialog.file"
          data-testid="book-import-submit"
          @click="doImport"
          >开始导入</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  listBooks,
  addBook,
  updateBook,
  deleteBook,
  downloadImportTemplate,
  importBooks
} from '@/api/book'
import { uploadBookCover } from '@/api/book'
import { listCategories } from '@/api/category'
import { listPublishers } from '@/api/publisher'
import { msg } from '@/utils/message'
import BookCover from '@/components/BookCover.vue'
import { saveBlobResponse } from '@/utils/download'

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)
const categories = ref([])
const publishers = ref([])
const query = reactive({ page: 1, size: 10, keyword: '', categoryId: '', onlyStock: false })

const dialog = reactive({ visible: false, form: {} })
// 封面地址加载失败标记：由 BookCover 的 load-error 事件置位，改地址时复位
const coverBroken = ref(false)
// 封面上传状态与选图即传（2026-10-25 新增：此前只能手填图片地址）
const coverUploading = ref(false)

async function onCoverFileChange(uploadFile) {
  const raw = uploadFile && uploadFile.raw
  if (!raw) return
  // 前端先挡一层，与后端白名单/上限一致（后端仍会再校验）
  if (raw.size > 2 * 1024 * 1024) {
    msg.warning('封面图片不能超过 2MB')
    return
  }
  coverUploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', raw)
    const res = await uploadBookCover(fd)
    dialog.form.cover = res.data
    coverBroken.value = false
    msg.success('封面已上传')
  } catch (err) {
    // 拦截器已提示
  } finally {
    coverUploading.value = false
  }
}

/** 真实可借数：扣掉"待取书"预约占用的副本（与读者端同口径） */
function availableStock(row) {
  return Math.max(0, Number(row?.stock || 0) - Number(row?.reservedCount || 0))
}

// —— 批量导入 ——
const importDialog = reactive({
  visible: false,
  file: null,
  fileName: '',
  fileList: [],
  uploading: false,
  result: null
})

function openImport() {
  importDialog.visible = true
  importDialog.file = null
  importDialog.fileName = ''
  importDialog.fileList = []
  importDialog.result = null
}

async function downloadTemplate() {
  try {
    const res = await downloadImportTemplate()
    saveBlobResponse(res, '图书导入模板.xlsx')
  } catch (e) {
    msg.error((e && e.message) || '模板下载失败，请重试')
  }
}

function onImportFileChange(file, fileList) {
  importDialog.file = file.raw
  importDialog.fileName = file.name
  importDialog.fileList = fileList.slice(-1)
  importDialog.result = null
}

function onImportFileRemove() {
  importDialog.file = null
  importDialog.fileName = ''
  importDialog.fileList = []
  importDialog.result = null
}

function onImportExceed() {
  msg.warning('一次只能选择一个文件，请先移除已选文件')
}

async function doImport() {
  if (!importDialog.file) {
    msg.warning('请先选择 Excel 文件')
    return
  }
  importDialog.uploading = true
  try {
    const formData = new FormData()
    formData.append('file', importDialog.file)
    const res = await importBooks(formData)
    importDialog.result = res.data
    msg.success(res.msg)
    loadList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    importDialog.uploading = false
  }
}

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.keyword) params.keyword = query.keyword
    if (query.categoryId !== '') params.categoryId = query.categoryId
    if (query.onlyStock) params.onlyStock = true
    const res = await listBooks(params)
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
  query.categoryId = ''
  query.onlyStock = false
  query.page = 1
  loadList()
}

function openDialog(row) {
  coverBroken.value = false
  if (row) {
    dialog.form = { ...row }
  } else {
    dialog.form = {
      id: null,
      isbn: '',
      title: '',
      author: '',
      categoryId: '',
      publisherId: '',
      publishDate: '',
      price: 0,
      totalStock: 0,
      stock: 0,
      location: '',
      cover: '',
      description: ''
    }
  }
  dialog.visible = true
}

/** 新增图书时总册数联动可借数（新书全部在架，保持“总册数=可借数+在借+已赔偿”自洽）；编辑时不联动，避免覆盖真实在借数 */
function onTotalStockChange(val) {
  if (!dialog.form.id) dialog.form.stock = val
}

async function save() {
  if (!dialog.form.title) {
    msg.warning('请填写书名')
    return
  }
  if (!dialog.form.isbn) {
    msg.warning('请填写 ISBN')
    return
  }
  if (!/^(?:\d{13}|\d{9}[\dX])$/.test(dialog.form.isbn)) {
    msg.warning('ISBN 格式不正确（13 位数字或 10 位数字/X）')
    return
  }
  if (!dialog.form.author) {
    msg.warning('请填写作者')
    return
  }
  if (!dialog.form.categoryId) {
    msg.warning('请选择分类')
    return
  }
  if (!dialog.form.publisherId) {
    msg.warning('请选择出版社')
    return
  }
  if (Number(dialog.form.stock) > Number(dialog.form.totalStock)) {
    msg.warning('可借数不能大于总册数')
    return
  }
  saving.value = true
  try {
    if (dialog.form.id) {
      await updateBook(dialog.form.id, dialog.form)
      msg.success('保存成功')
    } else {
      await addBook(dialog.form)
      msg.success('新增成功')
    }
    dialog.visible = false
    loadList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function removeBook(row) {
  try {
    await ElMessageBox.confirm(`确定删除图书「${row.title}」吗？删除后不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return // 用户取消
  }
  await deleteBook(row.id)
  msg.success('删除成功')
  loadList()
}

onMounted(async () => {
  loadList()
  // 挂载时加载分类与出版社，供查询与弹窗使用
  try {
    const [cRes, pRes] = await Promise.all([listCategories(), listPublishers()])
    categories.value = cRes.data
    publishers.value = pRes.data
  } catch (e) {
    // 加载失败由拦截器提示，页面不中断
  }
})
</script>

<style scoped>
/* 封面编辑区（2026-10-25）：左侧大预览，右侧上传 + 地址 + 提示 */
.cover-editor {
  display: flex;
  gap: 20px;
  align-items: flex-start;
  width: 100%;
}
.cover-preview {
  flex: 0 0 auto;
}
.cover-side {
  flex: 1 1 auto;
  min-width: 0;
}
.cover-tip {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
.cover-tip--warn {
  color: #e6a23c;
}
.cover-url {
  margin-top: 10px;
}
.import-file-name {
  margin-top: 8px;
  font-size: 12.5px;
  color: #5c7189;
}
.import-result {
  margin-top: 14px;
}
</style>
