#!/bin/bash
# ============================================================
# LibMS 后端停止脚本（Linux）
# 用法：bash deploy/stop.sh（项目根目录或任意目录执行均可）
# ============================================================

# 与 start.sh 同一口径：PID 固定落在 backend/app.pid，不依赖当前工作目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
PID_FILE="$BACKEND_DIR/app.pid"

if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE")
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "✅ 已停止后端进程 PID=$PID"
    rm -f "$PID_FILE"
  else
    echo "进程 $PID 已不存在"
    rm -f "$PID_FILE"
  fi
else
  # 兜底：按 jar 名查找进程
  PID=$(ps -ef | grep 'libms-backend' | grep -v grep | awk '{print $2}')
  if [ -n "$PID" ]; then
    kill $PID
    echo "✅ 已停止后端进程 PID=$PID"
  else
    echo "未发现运行中的后端进程"
  fi
fi
