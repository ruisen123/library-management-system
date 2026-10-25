# LibMS 图书借阅管理系统 · 前端

基于 **Vue3（Composition API + `<script setup>`）+ Vite + Element Plus + Pinia + Vue Router 4 + Axios** 的图书借阅管理系统前端，对应设计文档 `docs/05_设计文档总览.md` 与 `docs/06_UI风格设计文档.md`。

## 运行方式

```bash
npm install        # 安装依赖（首次）
npm run dev        # 启动开发服务器 http://localhost:5173
npm run build      # 生产构建
npm run lint       # ESLint 校验 + 自动修复
npm run format     # Prettier 格式化
```

**Mock 开关**：开发阶段默认走真实后端（`.env.development` 中 `VITE_USE_MOCK=false`），请求经 Vite 代理转发至 `http://localhost:8080`。
- 打开 Mock（后端未就绪时可独立演示）：`.env.development` 中改为 `VITE_USE_MOCK=true`

**演示账号**（密码均为 `123456`）：

| 账号 | 角色 | 默认首页 |
|------|------|----------|
| admin | 系统管理员 | 数据总览 |
| librarian01 | 图书管理员 | 图书档案管理 |
| reader001 | 读者 | 找书 |

## 目录结构

```
src/
├── api/              # 接口封装（auth/user/book/category/publisher/borrow/fine/stats/notice/log/profile/rule/audit/reservation/notification，共 15 个）
├── components/       # 公共组件（layout/ 等；原列的 Loading.vue 不存在，2026-10-24 R3 核实后删除该名）
├── router/           # 路由与权限守卫（三角色 + addRoute 动态路由，按钮级权限经模板 v-if）
├── stores/           # Pinia 状态（user.js）
├── styles/           # 全局样式（Element Plus 主色定制）
├── utils/            # 工具（request.js / mock.js / message.js / dict.js 枚举字典映射）
└── views/            # 页面（admin/ librarian/ reader/ Login.vue Profile.vue）
```

## 前端数据模型说明

统一响应结构（后端 Result 约定）：

```json
{ "code": 200, "msg": "ok", "data": { } }
```

分页结构：`data` 为 `{ "records": [], "total": 0, "current": 1, "size": 10 }`（`current` 为当前页码，非 `page`）

| 模块 | 方法 | 路径 | 请求参数 | 响应 data |
|------|------|------|----------|-----------|
| 认证 | POST | /auth/login | `{ username, password }` | `{ token, user }` |
| 用户 | GET | /users | `{ page, size, role, keyword, status }` | 分页用户列表 |
| 用户 | POST | /users | 用户对象 | `null` |
| 用户 | PUT | /users/{id} | 用户对象 | `null` |
| 用户 | PUT | /users/{id}/status | — | `null` |
| 用户 | PUT | /users/{id}/password | — | `null` |
| 用户 | DELETE | /users/{id} | — | `null` |
| 图书 | GET | /books | `{ page, size, keyword, categoryId, onlyStock }` | 分页图书 |
| 图书 | GET | /books/{id} | — | 图书详情 |
| 图书 | POST/PUT/DELETE | /books[/{id}] | 图书对象 | `null` |
| 分类 | GET | /categories | — | 分类列表 |
| 分类 | POST/PUT/DELETE | /categories[/{id}] | 分类对象 | `null` |
| 出版社 | GET | /publishers | — | 出版社列表 |
| 出版社 | POST/PUT/DELETE | /publishers[/{id}] | 出版社对象 | `null` |
| 借阅 | GET | /borrow-records | `{ page, size, keyword, status, userId, startDate, endDate }` | 分页借阅记录 |
| 借阅 | GET | /my-borrows | `{ page, size, status, history }` | 分页借阅记录（后端按 Token 强制过滤本人；`history=true` 时仅返回历史借阅、排除在借） |
| 借阅 | GET | /readers | `{ keyword }` | 读者列表（借书办理检索，有关键词 20 / 无关键词 100 条上限） |
| 借阅 | POST | /borrow | `{ userId, bookId }` | 借阅记录 |
| 借阅 | POST | /return | `{ recordId }` | `{ record, overdueDays, fineAmount }` |
| 借阅 | POST | /renew | `{ recordId }` | 借阅记录（应还日期 +30 天） |
| 罚金 | GET | /fines | `{ page, size, status, userId }` | 分页罚金（读者仅本人） |
| 罚金 | PUT | /fines/{id}/pay | — | `null` |
| 罚金 | POST | /fines/compensate | `{ recordId, amount, type, remark }` | `null` |
| 统计 | GET | /stats/overview·trend·pie·top | — | 对应统计数据 |
| 公告 | GET | /notices | `{ page, size, status, onlyPublished }` | 分页公告（读者仅见已发布） |
| 公告 | POST/PUT/DELETE | /notices[/{id}] | 公告对象 | `null` |
| 日志 | GET | /logs | `{ page, size, keyword }` | 分页日志 |
| 个人中心 | GET / PUT | /profile | 资料对象 | 当前用户 / `null` |
| 个人中心 | PUT | /profile/password | `{ oldPassword, newPassword }` | `null` |
| 规则 | GET | /rule/config | — | 借阅规则常量（只读） |

> 完整端点清单（共 **58** 个）与角色权限矩阵见 `docs/07_前后端联调说明.md` §2.1 / §2.3；本表为前端调用速查。
> 说明：写操作（POST / PUT / DELETE）统一返回 `Result<Void>`，即 `data` 为 `null`，前端仅依据 `code`/`msg` 判断结果。

> 开发进度：全部页面均已完整实现并交付（共 **17** 个页面组件：登录 / 数据总览 / 用户管理 / 图书档案管理 / 分类管理 / 出版社管理 / 公告 / 罚金管理 / 借还办理 / 借阅记录查询 / 借阅规则 / 操作日志 / 找书 / 图书详情 / 我的书架 / 消息中心 / 个人中心；其中图书详情与消息中心为不占侧边菜单的隐藏路由页）。
