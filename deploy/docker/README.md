# LibMS 容器化部署（deploy/docker）

一条命令拉起 MySQL + 后端 + 前端，用于换机演示与答辩现场快速起服务。

## 前置

- Docker Desktop（Windows 需已启动守护进程；`docker info` 能返回 Server Version 即可）
- 首次构建需联网拉取基础镜像与 Maven / npm 依赖

## 步骤

```bash
cd deploy/docker
cp .env.example .env      # 填 MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD / JWT_SECRET
docker compose up -d --build
# 打开 http://localhost:8088  登录 admin / 123456
```

常用命令：

```bash
docker compose ps                 # 看三个服务的 healthy 状态
docker compose logs -f backend    # 看后端日志
docker compose down               # 停止，保留数据卷
docker compose down -v           # 停止并清空数据卷（下次 up 重新导入 docs/04 种子）
```

## 端口与暴露面

| 服务 | 宿主端口 | 说明 |
|---|---|---|
| frontend | `${WEB_PORT:-8088}` → 80 | 唯一对外入口，`/api/**` 由容器内 Nginx 反代后端 |
| backend | 不映射 | 仅容器网络内 `backend:8080` |
| mysql | 不映射 | 仅容器网络内 `mysql:3306` |

> ⚠️ **Windows 上 8088 可能绑不上（2026-10-25 本机实测）**：报错形如
> `ports are not available: ... bind: An attempt was made to access a socket in a way forbidden by its access permissions`。
> 这不是端口被占用，而是 **Windows 的保留端口段**（装 Docker Desktop / WSL2 / Hyper-V 后会出现）把它圈走了。
> 自查：`netsh int ipv4 show excludedportrange protocol=tcp`，看 8088 是否落在某个区间的起止之间
> （本机实测保留段含 `8013-8112`、`8113-8212`，故 8088 / 8089 / 8090 / 8099 均不可用，**9088 / 18088 可用**）。
> 解法：把 `.env` 里 `WEB_PORT` 改成未落在保留段的端口（如 `9088`），再 `docker compose up -d`（**无需 --build**）。
> `CORS_ALLOWED_ORIGIN` 默认值已按 `${WEB_PORT}` 生成，**无需另行修改**；访问地址随之变为 `http://localhost:9088`。

> ⚠️ **`healthy` 不代表数据库连通（2026-10-24 审计补充）**：backend 的 healthcheck 只探
> `GET /books` 的 HTTP 码，而后端统一"HTTP 200 承载业务码"（无令牌也是 HTTP 200，401 只在响应体里），
> 所以 **healthy 说明"进程已能响应 HTTP"，不说明 DB 可用**；`depends_on: service_healthy`
> 保证的是启动顺序而非数据库就绪。数据库真的连不上时，会在登录等业务请求上报错，可直接用
> `docker compose logs -f backend` 看连接异常。

## 初始化与重置

- MySQL 官方镜像只在**空数据卷**首次启动时执行 `/docker-entrypoint-initdb.d/01-init.sql`
  （挂载的是仓库里的 `docs/04_数据库设计.sql`：建库 + 10 张表 + 19 个 CHECK + 种子数据）。
- 想回到干净演示态：`docker compose down -v && docker compose up -d`。

## 与本机直跑方式的关系

容器化是**新增的可选部署方式**，不替换原有的 `一键启动.bat` / `deploy/start.sh` 流程；
本机直跑仍按《从零开始运行手册》执行。两者共用同一份 `docs/04_数据库设计.sql`，
表结构与种子数据完全一致。

## 安全注意

- 口令与 `JWT_SECRET` 只放 `.env`，不要提交、不要随包外发。
- 生产环境请额外配置 HTTPS（在 Nginx 之前再加一层带证书的反代）。
- 邮件通道默认关闭；启用需填 `MAIL_*`，凭据同样只放 `.env`。
- **可信代理头**：compose 默认注入 `APP_TRUST_FORWARDED_HEADER=true`。容器内 Nginx 以
  `X-Forwarded-For: $remote_addr` 覆盖该头，客户端无法伪造；若改为手工部署且后端直接暴露，
  应将其设为 `false`，否则审计 IP 可被伪造。
- **接口文档**：compose 默认注入 `APP_API_DOCS_ENABLED=false`，应用层对 `/doc.html`、
  `/v3/api-docs` 返回 404，即使有人绕过 Nginx 直连后端端口也读不到接口清单。
  需要在容器内演示 Swagger 时，在 `.env` 中改为 `true` 并重启 backend。
