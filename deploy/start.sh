#!/bin/bash
# ============================================================
# LibMS 后端启动脚本（Linux）
# 用法：bash start.sh
# 说明：通过环境变量注入数据库账号密码，后台运行并输出日志到 app.log
# ============================================================

# ---- 请按实际环境填写以下配置 ----
export DB_HOST="localhost"
export DB_PORT="3306"
export DB_NAME="library_db"
export DB_USERNAME="root"
# 强制外部注入（fail-fast，与 application.yml 无默认值的安全哲学一致），
# 避免把真实口令写进脚本随交付扩散
export DB_PASSWORD="${DB_PASSWORD:?请先设置 DB_PASSWORD 环境变量}"
export JWT_SECRET="${JWT_SECRET:?请先设置 JWT_SECRET 环境变量}"
# 生产默认关闭接口文档面（与 compose 默认值一致；本地演示可显式置 true）
export APP_API_DOCS_ENABLED="${APP_API_DOCS_ENABLED:-false}"
export FILE_UPLOAD_DIR="/usr/local/libms/upload"

# 后端端口
PORT=8080
JAR="target/libms-backend-1.0.0.jar"

# ---- 校验 jar 是否存在 ----
if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，请先在 backend 目录执行：mvn clean package -DskipTests"
  exit 1
fi

# ---- 创建上传目录 ----
mkdir -p "$FILE_UPLOAD_DIR"

# ---- 后台启动 ----
echo "正在启动 LibMS 后端（端口 $PORT）..."
nohup java -jar "$JAR" --server.port=$PORT > app.log 2>&1 &
echo $! > app.pid

# ---- 等待并探活 ----
# 探活判定"能响应 HTTP 即可就绪"（非 000）而非固定 200：这样生产注入 APP_API_DOCS_ENABLED=false 关闭接口文档后
# （/doc.html 变 404）探活依然成立；同时不依赖任何账号口令，且循环等待避免慢主机误报
READY=0
for i in $(seq 1 30); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PORT/doc.html)
  if [ "$CODE" != "000" ]; then READY=1; break; fi
  sleep 1
done

if [ "$READY" = "1" ]; then
  echo "✅ 后端启动成功，PID=$(cat app.pid)，日志见 app.log"
  echo "   接口文档（默认关闭；置 APP_API_DOCS_ENABLED=true 可开启）：http://localhost:$PORT/doc.html"
else
  echo "⚠️ 后端 30 秒内未就绪，请查看 app.log 排查"
  tail -20 app.log
fi
