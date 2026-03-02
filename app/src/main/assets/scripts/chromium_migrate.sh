#!/system/bin/sh
# ============================================================
#  chromium_migrate.sh — Chromium Veri Göçü v1.1.0
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/common.sh"

SRC_PKG="$1"
DST_PKG="$2"

discover_chromium_profile() {
    for d in app_chrome app_chromium app_brave app_vivaldi; do
        [ -d "/data/data/$1/$d/Default" ] && echo "/data/data/$1/$d/Default" && return 0
    done
    return 1
}

migrate_chromium_main() {
    log_init
    check_root; check_sqlite3
    
    local src_prof=$(discover_chromium_profile "$SRC_PKG")
    [ -z "$src_prof" ] && exit 1
    check_disk_space "$src_prof" 50
    
    local dst_prof=$(discover_chromium_profile "$DST_PKG")
    [ -z "$dst_prof" ] && exit 1
    
    backup_profile "$dst_prof" "target_original"
    save_rollback_manifest "$DST_PKG" "CHROMIUM" "$dst_prof"
    
    log_phase "1" "VERITABANLARI"
    for f in Bookmarks History "Login Data" "Web Data" Cookies Favicons; do
        safe_copy_file "$src_prof/$f" "$dst_prof/$f" "$f"
    done
    
    log_phase "2" "EKLENTILER"
    safe_copy_dir "$src_prof/Extensions" "$dst_prof/Extensions" "Exts"
    
    log_phase "6" "TEMIZLIK"
    local uid=$(get_package_uid "$DST_PKG")
    [ -n "$uid" ] && fix_ownership_recursive "/data/data/$DST_PKG" "$uid"
    
    log_ok "Chromium gocu TAMAMLANDI!"
}

migrate_chromium_main
