#!/bin/bash
# ============================================================
# LibMS 操作日志清理脚本（Linux）—— 落实 O4 开放项（operation_log 无清理策略，见 审查归档/01_审查过程全记录/36_系统审查报告_第十六轮.md）
# 作用：删除 create_time 早于 RETENTION_DAYS（默认 180 天）的审计日志，控制表体积与查询退化。
# 用法：
#   1. 手动执行：        bash log_cleanup.sh
#   2. 干跑（只看语句）：DRY_RUN=1 bash log_cleanup.sh
#   3. 每周自动执行（crontab，每周日 03:00）：
#      0 3 * * 0 bash /usr/local/libms/deploy/log_cleanup.sh
# 说明：凭据经 --defaults-extra-file 传递，避免口令出现在进程命令行（与 backup.sh 同口径）；
#       审计日志属合规留存数据，默认保留 180 天，如需更长保留期请显式调整 RETENTION_DAYS。
# ============================================================

set -euo pipefail
umask 077

# ---- 环境变量（与 start.sh / backup.sh 一致，请按实际环境填写）----
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-<你的数据库密码>}"
DB_NAME="${DB_NAME:-library_db}"

# ---- 保留天数（默认 180 天，与 审查归档/01_审查过程全记录/33_性能与容量提升建议书.md 一致）----
RETENTION_DAYS="${RETENTION_DAYS:-180}"

SQL="DELETE FROM operation_log WHERE create_time < NOW() - INTERVAL ${RETENTION_DAYS} DAY;"

if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "[DRY_RUN] 将对 ${DB_NAME} 执行：${SQL}"
  exit 0
fi

# ---- 凭据经选项文件传递：避免口令出现在进程命令行（-p 方式可被同机用户经 ps -ef 看到）----
CRED_FILE="$(mktemp)"
trap 'rm -f "$CRED_FILE"' EXIT
cat > "$CRED_FILE" <<EOF
[client]
user=$DB_USERNAME
password="$DB_PASSWORD"
EOF
chmod 600 "$CRED_FILE"

# 兼容 Windows(Git Bash) 验证环境：mktemp 返回 MSYS 路径（/tmp/...），原生 mysql.exe 不认，
# 需用 cygpath 转为 Windows 路径；Linux 无 cygpath，原样使用（生产路径不受影响）
if command -v cygpath >/dev/null 2>&1; then CRED_ARG="$(cygpath -w "$CRED_FILE")"; else CRED_ARG="$CRED_FILE"; fi

Q() { mysql --defaults-extra-file="$CRED_ARG" -h"$DB_HOST" -P"$DB_PORT" -D "$DB_NAME" -N -e "$1"; }

BEFORE=$(Q "SELECT COUNT(*) FROM operation_log;")
# 注意：必须用 -D 指定库（或全限定表名），否则会报 "No database selected"（本脚本初版曾踩此坑）
mysql --defaults-extra-file="$CRED_ARG" -h"$DB_HOST" -P"$DB_PORT" -D "$DB_NAME" -e "$SQL"
AFTER=$(Q "SELECT COUNT(*) FROM operation_log;")

echo "✅ 日志清理完成：${BEFORE} → ${AFTER} 条（保留最近 ${RETENTION_DAYS} 天，共删除 $((BEFORE - AFTER)) 条）"
