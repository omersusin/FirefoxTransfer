#!/system/bin/sh
# ============================================================
#  common.sh — Browser Data Migrator: Ortak Yardımcılar
#  Sürüm: 1.1.0 (ADIM 5 düzeltmeleri)
# ============================================================

# ---- Yapılandırma ----
WORK_DIR="/data/local/tmp/browser_migrator"
LOG_FILE="${WORK_DIR}/migration.log"
BACKUP_DIR="${WORK_DIR}/backup_$(date +%Y%m%d_%H%M%S)"

# SQLite3 binary yolu — DataMover aynı dizine çıkarır
SQLITE3_BIN="${WORK_DIR}/sqlite3"

# ---- Renkler ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ============================================================
#  LOG
# ============================================================
log_init() {
    mkdir -p "$WORK_DIR"
    mkdir -p "$BACKUP_DIR"
    echo "=== Browser Migration Log ===" > "$LOG_FILE"
    echo "=== $(date) ===" >> "$LOG_FILE"
    echo "" >> "$LOG_FILE"
}

log_info() {
    local msg="[INFO] $1"
    echo -e "${CYAN}${msg}${NC}"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null
}

log_ok() {
    local msg="[OK]   $1"
    echo -e "${GREEN}${msg}${NC}"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null
}

log_warn() {
    local msg="[WARN] $1"
    echo -e "${YELLOW}${msg}${NC}"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null
}

log_error() {
    local msg="[ERR]  $1"
    echo -e "${RED}${msg}${NC}"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null
}

log_phase() {
    local msg="
============================================
  FAZA $1: $2
============================================"
    echo -e "${GREEN}${msg}${NC}"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null
}

# ============================================================
#  ROOT & TEMEL KONTROLLER
# ============================================================
check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        log_error "Root yetkisi gerekli! (uid=$(id -u))"
        exit 1
    fi
    log_ok "Root yetkisi dogruland."
}

check_sqlite3() {
    if [ -x "$SQLITE3_BIN" ]; then
        log_ok "SQLite3 (bundle): $SQLITE3_BIN"
        return 0
    fi
    if command -v sqlite3 >/dev/null 2>&1; then
        SQLITE3_BIN="sqlite3"
        log_ok "SQLite3 (sistem): $(which sqlite3)"
        return 0
    fi
    for loc in /system/bin/sqlite3 /system/xbin/sqlite3 \
               /data/adb/modules/sqlite3/system/bin/sqlite3 \
               /data/local/tmp/sqlite3; do
        if [ -x "$loc" ]; then
            SQLITE3_BIN="$loc"
            log_ok "SQLite3 (bulunan): $loc"
            return 0
        fi
    done
    log_warn "sqlite3 bulunamadi! SQLite yamalama atlanacak."
    SQLITE3_BIN=""
    return 1
}

check_package_exists() {
    local pkg="$1"
    if ! pm list packages 2>/dev/null | grep -q "package:${pkg}$"; then
        log_error "Paket bulunamadi: $pkg"
        return 1
    fi
    log_ok "Paket mevcut: $pkg"
    return 0
}

get_package_uid() {
    local pkg="$1"
    local data_dir="/data/data/${pkg}"
    if [ -d "$data_dir" ]; then
        stat -c '%u' "$data_dir" 2>/dev/null || \
        ls -ldn "$data_dir" 2>/dev/null | awk '{print $3}'
    else
        echo ""
    fi
}

force_stop_package() {
    local pkg="$1"
    log_info "Paket durduruluyor: $pkg"
    am force-stop "$pkg" 2>/dev/null
    sleep 1
    local pids=$(pidof "$pkg" 2>/dev/null)
    if [ -n "$pids" ]; then
        log_warn "PID'ler aktif, SIGKILL: $pids"
        kill -9 $pids 2>/dev/null
        sleep 1
    fi
    log_ok "Paket durduruldu: $pkg"
}

# ============================================================
#  DİSK ALANI KONTROLÜ
# ============================================================
check_disk_space() {
    local src_path="$1"
    local min_extra_mb="${2:-50}"

    if [ ! -d "$src_path" ]; then
        log_warn "Kaynak dizin yok, disk kontrolü atlanıyor: $src_path"
        return 0
    fi

    local src_size_kb=$(du -sk "$src_path" 2>/dev/null | awk '{print $1}')
    [ -z "$src_size_kb" ] || [ "$src_size_kb" = "0" ] && return 0

    local src_size_mb=$((src_size_kb / 1024))
    local needed_mb=$((src_size_mb + min_extra_mb))
    local avail_kb=$(df /data 2>/dev/null | tail -1 | awk '{print $4}')
    [ -z "$avail_kb" ] && return 0

    local avail_mb=$((avail_kb / 1024))
    log_info "Kaynak profil: ${src_size_mb}MB | Gereken: ${needed_mb}MB | Mevcut: ${avail_mb}MB"

    if [ "$avail_mb" -lt "$needed_mb" ]; then
        log_error "YETERSIZ DISK ALANI! Gereken: ${needed_mb}MB, Mevcut: ${avail_mb}MB"
        exit 1
    fi
    log_ok "Disk alani yeterli."
    return 0
}

# ============================================================
#  DOSYA İŞLEMLERİ
# ============================================================
safe_copy_file() {
    local src="$1" dst="$2" desc="$3"
    [ ! -f "$src" ] && return 1
    if [ -f "$dst" ]; then
        local bk="${BACKUP_DIR}/$(echo "$dst" | sed 's|/|_|g')"
        mkdir -p "$(dirname "$bk")"
        cp -f "$dst" "$bk" 2>/dev/null
    fi
    cp -f "$src" "$dst"
    [ $? -eq 0 ] && log_ok "Kopyalandi: $desc" || log_error "Kopyalama basarisiz: $desc"
}

safe_copy_dir() {
    local src="$1" dst="$2" desc="$3"
    [ ! -d "$src" ] && return 1
    if [ -d "$dst" ]; then
        local bk="${BACKUP_DIR}/$(echo "$dst" | sed 's|/|_|g')"
        mkdir -p "$bk"
        cp -rf "$dst" "$bk/" 2>/dev/null
    fi
    mkdir -p "$dst"
    cp -rf "$src/." "$dst/"
    [ $? -eq 0 ] && log_ok "Dizin kopyalandi: $desc" || log_error "Dizin kopyalama basarisiz: $desc"
}

fix_ownership_recursive() {
    local path="$1" uid="$2" gid="${3:-$uid}"
    [ -z "$uid" ] || [ ! -e "$path" ] && return 1
    chown -R "${uid}:${gid}" "$path" 2>/dev/null
    find "$path" -type d -exec chmod 700 {} \; 2>/dev/null
    find "$path" -type f -exec chmod 600 {} \; 2>/dev/null
    log_ok "Sahiplik duzeltildi: $path (uid=$uid)"
}

fix_selinux_context() {
    local path="$1"
    if command -v restorecon >/dev/null 2>&1; then
        restorecon -RF "$path" 2>/dev/null
    else
        chcon -R "u:object_r:app_data_file:s0" "$path" 2>/dev/null
    fi
}

backup_profile() {
    local profile_path="$1" label="$2"
    local bk="${BACKUP_DIR}/${label}_profile"
    mkdir -p "$bk"
    cp -rf "$profile_path/." "$bk/"
    [ $? -eq 0 ] && log_ok "Yedekleme tamam: $label" || log_error "Yedekleme basarisiz: $label"
}

# ============================================================
#  GERİ ALMA (ROLLBACK)
# ============================================================
save_rollback_manifest() {
    local dst_pkg="$1" engine="$2" dst_profile="$3"
    local manifest="${BACKUP_DIR}/rollback_manifest.txt"
    cat > "$manifest" << EOF
TARGET_PACKAGE=$dst_pkg
ENGINE=$engine
TARGET_PROFILE=$dst_profile
BACKUP_DIR=$BACKUP_DIR
EOF
}

perform_rollback() {
    local backup_dir="$1"
    local manifest="${backup_dir}/rollback_manifest.txt"
    [ ! -f "$manifest" ] && return 1
    local target_pkg=$(grep "^TARGET_PACKAGE=" "$manifest" | cut -d'=' -f2)
    local target_profile=$(grep "^TARGET_PROFILE=" "$manifest" | cut -d'=' -f2)
    
    force_stop_package "$target_pkg"
    local backup_profile="${backup_dir}/target_original_profile"
    if [ -d "$backup_profile" ]; then
        rm -rf "$target_profile"
        mkdir -p "$target_profile"
        cp -rf "$backup_profile/." "$target_profile/"
        local uid=$(get_package_uid "$target_pkg")
        [ -n "$uid" ] && fix_ownership_recursive "$target_profile" "$uid" && fix_selinux_context "$target_profile"
        log_ok "Geri alma tamamlandi."
        return 0
    fi
    return 1
}

log_info "common.sh v1.1.0 yuklendi."
