#!/bin/bash
# ============================================================
# LibMS 后端启动脚本（Linux）
# 用法：bash deploy/start.sh（项目根目录或任意目录执行均可）
# 说明：通过环境变量注入数据库账号密码，后台运行并输出日志到 backend/app.log
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
export FILE_UPLOAD_DIR="${FILE_UPLOAD_DIR:-/usr/local/libms/upload}"

# 后端端口
PORT=8080
# 脚本可从任意目录执行：按脚本位置定位 backend/target，避免依赖当前工作目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
JAR="$BACKEND_DIR/target/libms-backend-1.0.0.jar"

# ---- 校验 jar 是否存在 ----
if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，请先在 backend 目录执行：mvn clean package -DskipTests"
  exit 1
fi

# ---- 创建上传目录 ----
mkdir -p "$FILE_UPLOAD_DIR"

# ---- 后台启动（日志/PID 落在 backend 目录，与手工部署口径一致）----
cd "$BACKEND_DIR"
echo "正在启动 LibMS 后端（端口 $PORT）..."
nohup java -jar "$JAR" --server.port=$PORT > app.log 2>&1 &
echo $! > app.pid

# ---- 等待并探活 ----
# 用 /books 探活：未登录也会返回 HTTP 200（业务码 401 在响应体），不依赖账号口令，
# 也不受 APP_API_DOCS_ENABLED=false 时 /doc.html 返回 404 的影响
READY=0
for i in $(seq 1 30); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PORT/books)
  if [ "$CODE" != "000" ]; then READY=1; break; fi
  sleep 1
done

if [ "$READY" = "1" ]; then
  echo "✅ 后端启动成功，PID=$(cat app.pid)，日志见 $BACKEND_DIR/app.log"
  echo "   接口文档（默认关闭；置 APP_API_DOCS_ENABLED=true 可开启）：http://localhost:$PORT/doc.html"
else
  echo "⚠️ 后端 30 秒内未就绪，请查看 $BACKEND_DIR/app.log 排查"
  tail -20 app.log
fi
