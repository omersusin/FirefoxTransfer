#!/system/bin/sh
# ============================================================
#  common.sh v3 — stdout/stderr ayrımı düzeltildi
# ============================================================

WORK_DIR="/data/local/tmp/browser_migrator"
LOG_FILE="${WORK_DIR}/migration.log"
BACKUP_DIR=""
SQLITE3_BIN=""

# ============================================================
#  LOG — HER ZAMAN stderr'e yaz (stdout'u kirletme)
#  Kotlin tarafında 2>&1 ile birleştirildiği için
#  kullanıcı her şeyi görür
# ============================================================
log_init() {
    mkdir -p "$WORK_DIR" 2>/dev/null
    BACKUP_DIR="${WORK_DIR}/backup_$(date +%Y%m%d_%H%M%S 2>/dev/null || echo unknown)"
    mkdir -p "$BACKUP_DIR" 2>/dev/null
    echo "=== Migration Log — $(date) ===" > "$LOG_FILE" 2>/dev/null
}

log_info() {
    echo "[INFO] $*" >&2
    echo "[INFO] $*" >> "$LOG_FILE" 2>/dev/null
}

log_ok() {
    echo "[OK]   $*" >&2
    echo "[OK]   $*" >> "$LOG_FILE" 2>/dev/null
}

log_warn() {
    echo "[WARN] $*" >&2
    echo "[WARN] $*" >> "$LOG_FILE" 2>/dev/null
}

log_error() {
    echo "[ERR]  $*" >&2
    echo "[ERR]  $*" >> "$LOG_FILE" 2>/dev/null
}

log_phase() {
    echo "[PHASE] $*" >&2
    echo "[PHASE] $*" >> "$LOG_FILE" 2>/dev/null
}

# ============================================================
#  PAKET VERİ DİZİNİNİ BUL
#  /data/data symlink'i çalışmayabilir — birden fazla yol dene
# ============================================================
find_data_dir() {
    local pkg="$1"

    # Yontem 1: /data/data (en yaygin)
    if [ -d "/data/data/$pkg" ]; then
        echo "/data/data/$pkg"
        return 0
    fi

    # Yontem 2: /data/user/0 (gercek yol)
    if [ -d "/data/user/0/$pkg" ]; then
        echo "/data/user/0/$pkg"
        return 0
    fi

    # Yontem 3: dumpsys ile sor
    local dd
    dd=$(dumpsys package "$pkg" 2>/dev/null | grep "dataDir=" | head -1 | sed 's/.*dataDir=//' | tr -d ' \r')
    if [ -n "$dd" ] && [ -d "$dd" ]; then
        echo "$dd"
        return 0
    fi

    # Yontem 4: pm ile sor
    dd=$(pm dump "$pkg" 2>/dev/null | grep "dataDir=" | head -1 | sed 's/.*dataDir=//' | tr -d ' \r')
    if [ -n "$dd" ] && [ -d "$dd" ]; then
        echo "$dd"
        return 0
    fi

    # Yontem 5: /data/user altinda ara
    for uid_dir in /data/user/*/; do
        if [ -d "${uid_dir}${pkg}" ]; then
            echo "${uid_dir}${pkg}"
            return 0
        fi
    done

    echo ""
    return 1
}

# ============================================================
#  ROOT
# ============================================================
check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        log_error "Root yetkisi gerekli!"
        exit 1
    fi
    log_ok "Root dogrulandi"
}

# ============================================================
#  SQLITE3
# ============================================================
check_sqlite3() {
    for p in \
        "${WORK_DIR}/sqlite3" \
        /system/bin/sqlite3 \
        /system/xbin/sqlite3 \
        /data/local/tmp/sqlite3 \
        /data/adb/magisk/sqlite3 \
    ; do
        if [ -x "$p" ] 2>/dev/null; then
            SQLITE3_BIN="$p"
            log_ok "sqlite3: $p"
            return 0
        fi
    done

    if command -v sqlite3 >/dev/null 2>&1; then
        SQLITE3_BIN="sqlite3"
        log_ok "sqlite3: $(command -v sqlite3)"
        return 0
    fi

    log_warn "sqlite3 bulunamadi — SQLite yamalama atlanacak"
    SQLITE3_BIN=""
    return 1
}

# ============================================================
#  PAKET
# ============================================================
check_package() {
    if pm list packages 2>/dev/null | grep -q "package:${1}$"; then
        log_ok "Paket mevcut: $1"
        return 0
    fi
    log_error "Paket yok: $1"
    return 1
}

get_uid() {
    local dd
    dd=$(find_data_dir "$1")
    if [ -n "$dd" ]; then
        stat -c '%u' "$dd" 2>/dev/null
    fi
}

stop_pkg() {
    log_info "Durduruluyor: $1"
    am force-stop "$1" 2>/dev/null
    sleep 1
    log_ok "Durduruldu: $1"
}

# ============================================================
#  DISK ALANI
# ============================================================
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

# ============================================================
#  KOPYALAMA
# ============================================================
safe_cp() {
    local src="$1" dst="$2" desc="$3"

    if [ ! -e "$src" ]; then
        log_warn "Yok, atlaniyor: $desc"
        return 1
    fi

    # yedek
    if [ -e "$dst" ] && [ -n "$BACKUP_DIR" ]; then
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

# ============================================================
#  İZİNLER
# ============================================================
fix_perms() {
    local path="$1" uid="$2"
    if [ -z "$uid" ] || [ ! -e "$path" ]; then
        log_error "Izin duzeltilemedi: $path"
        return 1
    fi
    chown -R "${uid}:${uid}" "$path" 2>/dev/null
    find "$path" -type d -exec chmod 700 {} \; 2>/dev/null
    find "$path" -type f -exec chmod 600 {} \; 2>/dev/null
    if command -v restorecon >/dev/null 2>&1; then
        restorecon -RF "$path" 2>/dev/null
    fi
    log_ok "Izinler duzeltildi: $path"
}

# ============================================================
#  YEDEK MANİFEST
# ============================================================
save_manifest() {
    if [ -n "$BACKUP_DIR" ]; then
        cat > "${BACKUP_DIR}/manifest.txt" << MFEOF
TARGET=$1
ENGINE=$2
PROFILE=$3
DATE=$(date)
MFEOF
    fi
}

log_info "common.sh v3 yuklendi"
