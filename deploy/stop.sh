#!/bin/bash
# ============================================================
# LibMS 后端停止脚本（Linux）
# 用法：bash stop.sh
# ============================================================

if [ -f app.pid ]; then
  PID=$(cat app.pid)
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "✅ 已停止后端进程 PID=$PID"
    rm -f app.pid
  else
    echo "进程 $PID 已不存在"
    rm -f app.pid
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
