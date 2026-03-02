#!/system/bin/sh
# ============================================================
#  common.sh — Ortak yardımcılar (v2 — renk kodsuz)
# ============================================================

WORK_DIR="/data/local/tmp/browser_migrator"
LOG_FILE="${WORK_DIR}/migration.log"
# date komutu bazı Android'lerde kısıtlı olabilir, hata verirse fallback yap
BACKUP_DIR="${WORK_DIR}/backup_$(date +%Y%m%d_%H%M%S 2>/dev/null || echo unknown)"
SQLITE3_BIN=""

# ---- LOG ----
log_init() {
    mkdir -p "$WORK_DIR" 2>/dev/null
    mkdir -p "$BACKUP_DIR" 2>/dev/null
    echo "=== Migration Log — $(date) ===" > "$LOG_FILE" 2>/dev/null
}

log_info()  { echo "[INFO] $*";  echo "[INFO] $*"  >> "$LOG_FILE" 2>/dev/null; }
log_ok()    { echo "[OK]   $*";  echo "[OK]   $*"  >> "$LOG_FILE" 2>/dev/null; }
log_warn()  { echo "[WARN] $*";  echo "[WARN] $*"  >> "$LOG_FILE" 2>/dev/null; }
log_error() { echo "[ERR]  $*";  echo "[ERR]  $*"  >> "$LOG_FILE" 2>/dev/null; }

# ---- ROOT ----
check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        log_error "Root yetkisi gerekli!"
        exit 1
    fi
    log_ok "Root dogrulandi"
}

# ---- SQLITE3 ----
check_sqlite3() {
    for p in sqlite3 /system/bin/sqlite3 /system/xbin/sqlite3 \
             "${WORK_DIR}/sqlite3" /data/local/tmp/sqlite3 \
             /data/adb/modules/*/system/bin/sqlite3; do
        if [ -x "$p" ] 2>/dev/null; then
            SQLITE3_BIN="$p"
            log_ok "sqlite3: $p"
            return 0
        fi
    done
    # komut olarak dene
    if command -v sqlite3 >/dev/null 2>&1; then
        SQLITE3_BIN="sqlite3"
        log_ok "sqlite3: $(command -v sqlite3)"
        return 0
    fi
    log_warn "sqlite3 bulunamadi — SQLite yamalama atlanacak"
    return 1
}

# ---- PAKET ----
check_package() {
    if pm list packages 2>/dev/null | grep -q "package:${1}$"; then
        log_ok "Paket mevcut: $1"
        return 0
    fi
    log_error "Paket yok: $1"
    return 1
}

get_uid() {
    stat -c '%u' "/data/data/$1" 2>/dev/null
}

stop_pkg() {
    log_info "Durduruluyor: $1"
    am force-stop "$1" 2>/dev/null
    sleep 1
    log_ok "Durduruldu: $1"
}

# ---- DISK ALANI ----
check_disk() {
    local src_path="$1"
    if [ ! -d "$src_path" ]; then return 0; fi

    local src_kb
    src_kb=$(du -sk "$src_path" 2>/dev/null | awk '{print $1}')
    if [ -z "$src_kb" ]; then return 0; fi

    local need_mb=$(( (src_kb / 1024) + 50 ))
    local avail_kb
    avail_kb=$(df /data 2>/dev/null | tail -1 | awk '{print $4}')
    if [ -z "$avail_kb" ]; then return 0; fi

    local avail_mb=$((avail_kb / 1024))
    log_info "Disk: ${avail_mb}MB mevcut, ${need_mb}MB gerekli"

    if [ "$avail_mb" -lt "$need_mb" ] 2>/dev/null; then
        log_error "YETERSIZ DISK ALANI!"
        exit 1
    fi
    return 0
}

# ---- KOPYALAMA ----
safe_cp() {
    local src="$1" dst="$2" desc="$3"

    if [ ! -e "$src" ]; then
        log_warn "Yok, atlaniyor: $desc"
        return 1
    fi

    # yedek
    if [ -e "$dst" ]; then
        local bk="${BACKUP_DIR}/$(echo "$dst" | tr '/' '_')"
        cp -rf "$dst" "$bk" 2>/dev/null
    fi

    if [ -d "$src" ]; then
        mkdir -p "$dst" 2>/dev/null
        cp -rf "$src/." "$dst/" 2>/dev/null
    else
        mkdir -p "$(dirname "$dst")" 2>/dev/null
        cp -f "$src" "$dst" 2>/dev/null
    fi

    if [ $? -eq 0 ]; then
        log_ok "Kopyalandi: $desc"
    else
        log_error "Kopyalama basarisiz: $desc"
        return 1
    fi
}

# ---- İZİNLER ----
fix_perms() {
    local path="$1" uid="$2"
    if [ -z "$uid" ] || [ ! -e "$path" ]; then
        log_error "Izin duzeltilemedi: $path"
        return 1
    fi
    chown -R "${uid}:${uid}" "$path" 2>/dev/null
    find "$path" -type d -exec chmod 700 {} \; 2>/dev/null
    find "$path" -type f -exec chmod 600 {} \; 2>/dev/null
    # SELinux
    if command -v restorecon >/dev/null 2>&1; then
        restorecon -RF "$path" 2>/dev/null
    fi
    log_ok "Izinler duzeltildi: $path"
}

# ---- YEDEK MANİFEST ----
save_manifest() {
    cat > "${BACKUP_DIR}/manifest.txt" << EOF
TARGET=$1
ENGINE=$2
PROFILE=$3
DATE=$(date)
EOF
}

log_info "common.sh yuklendi"
