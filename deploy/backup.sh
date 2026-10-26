#!/bin/bash
# ============================================================
# LibMS 数据库自动备份脚本（Linux）
# 用法：
#   1. 手动执行：bash backup.sh
#   2. 每日自动备份（crontab，每日凌晨 2:00）：
#      0 2 * * * bash /usr/local/libms/deploy/backup.sh
# 说明：mysqldump 全量备份 library_db，保留最近 7 天，自动清理过期备份。
#       备份文件落盘后建议定期拷贝至异机/对象存储，防止同机磁盘故障导致备份一并丢失。
# ============================================================

# ---- 环境变量（与 start.sh 一致，请按实际环境填写）----
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-3306}"
export DB_USERNAME="${DB_USERNAME:-root}"
export DB_PASSWORD="${DB_PASSWORD:-<你的数据库密码>}"
export DB_NAME="${DB_NAME:-library_db}"

# ---- 备份目录与保留天数 ----
# 备份包含全库数据（口令密文、读者个人信息），默认仅属主可读写，防止同机其他账户读取
umask 077
BACKUP_DIR="${BACKUP_DIR:-/usr/local/libms/backup}"
KEEP_DAYS="${KEEP_DAYS:-7}"

mkdir -p "$BACKUP_DIR"

# ---- 凭据经选项文件传递：避免口令出现在进程命令行（-p 方式可被同机用户经 ps -ef 看到）----
CRED_FILE="$BACKUP_DIR/.my.cnf"
cat > "$CRED_FILE" <<EOF
[client]
user=$DB_USERNAME
password="$DB_PASSWORD"
EOF
chmod 600 "$CRED_FILE"

# ---- 执行备份（带日期时间戳，避免同日覆盖）----
TIMESTAMP=$(date +%F_%H%M%S)
OUT_FILE="$BACKUP_DIR/library_${TIMESTAMP}.sql"
# --databases 让 dump 自带 CREATE DATABASE IF NOT EXISTS 与 USE：
# 目标库被删或换机后可直接恢复，不被"必须先手工建库"卡住
if mysqldump --defaults-extra-file="$CRED_FILE" \
    -h"$DB_HOST" -P"$DB_PORT" --single-transaction --set-gtid-purged=OFF --databases "$DB_NAME" > "$OUT_FILE" 2> "$BACKUP_DIR/backup.err"; then
  chmod 600 "$OUT_FILE"
  echo "✅ 备份成功：$OUT_FILE（$(du -h "$OUT_FILE" | cut -f1)）"
  rm -f "$BACKUP_DIR/backup.err" "$CRED_FILE"
else
  echo "❌ 备份失败，请查看 $BACKUP_DIR/backup.err 排查"
  rm -f "$CRED_FILE"
  exit 1
fi

# ---- 清理过期备份（保留最近 KEEP_DAYS 天）----
find "$BACKUP_DIR" -name "library_*.sql" -mtime +$KEEP_DAYS -delete
echo "已清理 ${KEEP_DAYS} 天前的旧备份"
