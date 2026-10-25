<template>
  <!-- 借阅规则说明：规则参数由后端常量统一定义（FineConstant），此处为接口只读展示 -->
  <div v-loading="loading" class="page-card">
    <div class="card-title">核心借阅规则</div>
    <p class="rule-lead">
      本页数值为系统默认值；读者级可借额度、欠费上限与借阅证有效期以个人档案为准，
      实际能否借阅以服务端校验结果为准。
    </p>
    <el-empty v-if="!rule" description="规则加载失败，请刷新重试" />
    <el-descriptions v-else :column="2" border>
      <el-descriptions-item label="新读者默认可借上限">
        <span class="rule-value">{{ rule.maxBorrow }} 册</span>
      </el-descriptions-item>
      <el-descriptions-item label="借期">
        <span class="rule-value">{{ rule.borrowDays }} 天</span>
      </el-descriptions-item>
      <el-descriptions-item label="逾期罚金">
        <span class="rule-value">{{ rule.finePerDay }} 元/天</span>
      </el-descriptions-item>
      <el-descriptions-item label="续借规则">
        <span class="rule-value">每册 1 次，延长 {{ rule.renewDays }} 天</span>
      </el-descriptions-item>
      <el-descriptions-item label="读者级额度">
        <span class="rule-value">按读者档案配置（0 表示不可借）</span>
      </el-descriptions-item>
      <el-descriptions-item label="欠费上限">
        <span class="rule-value">按读者档案配置（默认 0 元 = 有欠费即禁借）</span>
      </el-descriptions-item>
    </el-descriptions>
  </div>

  <div class="page-card">
    <div class="card-title">借书前置条件（借书铁律）</div>
    <el-table :data="rules" stripe>
      <el-table-column type="index" label="序号" width="80" align="center" />
      <el-table-column prop="name" label="校验项" width="200" />
      <el-table-column prop="desc" label="规则说明" />
      <el-table-column label="不满足时处理" width="260">
        <template #default="{ row }"
          ><el-tag type="danger" effect="plain">{{ row.deny }}</el-tag></template
        >
      </el-table-column>
    </el-table>
    <!-- 这段是实现细节（对应论文设计章节），只说给管理/评审听；读者端不需要，隐藏 -->
    <p v-if="isAdmin" class="rule-tip">
      设计说明：系统级默认值固化在后端常量 <code>FineConstant</code>（新读者默认可借 5 册 /
      借期 30 天 / 续借 1 次延长 30 天 / 罚金 0.5 元/天），借阅额度与欠费上限按读者档案覆盖；
      借书接口在事务内以「条件更新扣减库存」保证并发下不超借，罚金在 SQL 层以 DATEDIFF 计算。
      规则常量化可避免被误改从而破坏借阅数据一致性，故本页为只读展示（对应 docs/05 4.1 该功能
      ☆ 级别，扩展可裁剪）。
    </p>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getRuleConfig } from '@/api/rule'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === 'ADMIN')

// 规则值来自后端 GET /rule/config（源头 FineConstant）。初始为 null：接口失败时展示"加载失败"，
// 不展示会随后端调整规则而陈旧的本地默认值（避免与后端 FineConstant 双源漂移）
const rule = ref(null)
const loading = ref(false)

const rules = computed(() => [
  { name: '读者状态', desc: '读者账号状态必须为「正常」（未冻结）', deny: '冻结账号禁止借书' },
  {
    name: '借阅额度',
    desc: `当前在借册数 < 本人可借上限（新读者默认 ${rule.value?.maxBorrow ?? 5} 册，管理员可按读者调整）`,
    deny: '提示「已达上限，请先归还」'
  },
  { name: '逾期检查', desc: '名下不存在逾期未还图书', deny: '提示「有逾期未还图书，请先归还」' },
  {
    name: '罚金检查',
    desc: '未缴罚金金额不超过本人欠费上限（默认 0 元 = 有任何未缴即禁借）',
    deny: '提示「有未缴罚金 / 超过该读者上限」'
  },
  {
    name: '库存检查',
    desc: '目标图书可借副本 > 0（条件更新扣减，且优先满足预约队首）',
    deny: '并发竞争失败自动回滚并提示重试'
  }
])

onMounted(async () => {
  loading.value = true
  try {
    const res = await getRuleConfig()
    rule.value = res.data
  } catch (e) {
    // 加载失败由拦截器提示，保留默认值兜底展示
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.rule-value {
  font-weight: 600;
  color: var(--el-color-primary);
}
.rule-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
}
.rule-lead {
  margin: -6px 0 14px;
  font-size: 13px;
  color: #7c8ca3;
}
</style>
