# LibMS 图书借阅管理系统 · 后端

基于 **Spring Boot 3.2.5 + MyBatis-Plus 3.5.5 + MySQL 8.0** 的图书借阅管理系统后端，对应设计文档 `docs/05_设计文档总览.md`。

## 技术栈

| 组件 | 说明 |
|------|------|
| Spring Boot 3.2.5 | 基础框架（JDK 21） |
| MyBatis-Plus 3.5.5 | ORM，免写基础 CRUD + 分页插件 |
| MySQL 8.0 | 数据库（库名 `library_db`） |
| jjwt 0.12.5 | 登录令牌（JWT） |
| Knife4j 4.4.0 | 接口文档（`/doc.html`） |
| Hutool 5.8.27 | 工具库（BCrypt 密码加密等） |

## 目录结构

```
src/main/java/com/library/
├── annotation/    # 自定义注解（@RequireRole 角色权限）
├── common/        # Result / ResultCode / LoginUser / 常量 / 全局异常
├── config/        # 配置（CORS / MyBatis-Plus 分页 / WebMvc / Knife4j）
├── controller/    # 控制层（RESTful）
├── dto/           # 请求参数对象
├── entity/        # 实体（对应 10 张表）
├── interceptor/   # 登录认证拦截器
├── mapper/        # MyBatis-Plus Mapper
├── service/       # 服务层（接口 + impl）
├── utils/         # 工具（JwtUtils / UserContext）
└── vo/            # 响应数据对象
```

## 数据库初始化

1. 确保本机已安装 MySQL 8.0 并启动；
2. 执行建表脚本（含建库、建表、测试数据、索引、核对查询）：

```bash
mysql -u root -p --default-character-set=utf8mb4 < ../docs/04_数据库设计.sql
```

> ⚠️ **Windows 下必须加 `--default-character-set=utf8mb4`**，否则导入中文数据会报 `ERROR 1406: Data too long`。

3. 或使用 Navicat / DataGrip 打开 `docs/04_数据库设计.sql` 整体执行。

## 运行方式

```bash
# 1. 配置数据库连接与 JWT 密钥（均通过环境变量注入，不硬编码）
#    DB_PASSWORD / JWT_SECRET 无默认值、必须显式设置（缺失则启动即失败，fail-fast）
#    Windows(PowerShell):  $env:DB_USERNAME="root"; $env:DB_PASSWORD="你的密码"; $env:JWT_SECRET="随机长密钥(≥32字节)"
#    Linux/Mac:           export DB_USERNAME=root DB_PASSWORD=你的密码 JWT_SECRET=随机长密钥

# 2. 编译打包
mvn clean package -DskipTests

# 3. 启动（默认端口 8080）
mvn spring-boot:run
# 或运行打包产物：
java -jar target/libms-backend-1.0.0.jar
```

## 数据库连接测试方法

1. **启动后看日志**：出现 `Started LibraryApplication` 且无 `Communications link failure` 表示连接成功；
2. **登录接口测试**（curl）：

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

   预期返回 `{"code":200,...,"data":{"token":"...","user":{...}}}`；
3. **接口文档**：浏览器访问 `http://localhost:8080/doc.html`，在文档页可直接调试登录接口。

## 测试账号

| 账号 | 角色 | 密码 |
|------|------|------|
| admin | 系统管理员 | 123456 |
| librarian01 | 图书管理员 | 123456 |
| reader001 | 读者 | 123456 |

## 接口约定

- 统一响应结构：`{ "code": 200, "msg": "操作成功", "data": {} }`（code=200 成功，401 未登录，403 无权限）
- 认证方式：请求头 `token` 携带 JWT（`jwt.header` 配置）
- 分页返回：`data` 为 `{ records: [], total, size, current }`

> 开发进度：全部 12 个模块（认证 / 用户 / 图书 / 分类 / 出版社 / 借阅 / 罚金 / 统计 / 公告 / 日志 / 个人中心 / 规则）均已交付并通过联调测试。
