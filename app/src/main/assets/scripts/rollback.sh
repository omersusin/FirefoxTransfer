#!/system/bin/sh
# ============================================================
#  rollback.sh — Rollback Migration
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/common.sh"

BACKUP_PATH="$1"
[ -z "$BACKUP_PATH" ] && BACKUP_PATH=$(ls -dt /data/local/tmp/browser_migrator/backup_* 2>/dev/null | head -1)

if [ -z "$BACKUP_PATH" ] || [ ! -d "$BACKUP_PATH" ]; then
    echo "[ERR] Backup not found!"; exit 1
fi

log_init
log_info "ROLLBACK: $BACKUP_PATH"
check_root
perform_rollback "$BACKUP_PATH"
exit $?
