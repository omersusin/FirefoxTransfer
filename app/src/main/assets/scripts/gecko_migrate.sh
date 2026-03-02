#!/system/bin/sh
# ============================================================
#  gecko_migrate.sh — GeckoView Veri Göçü v1.1.0
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/common.sh"

SRC_PKG="$1"
DST_PKG="$2"

discover_gecko_profile() {
    local mozilla_dir="/data/data/${1}/files/mozilla"
    [ ! -d "$mozilla_dir" ] && return 1
    local p=$(grep -m1 "^Path=" "$mozilla_dir/profiles.ini" 2>/dev/null | cut -d'=' -f2 | tr -d '\r')
    [ -n "$p" ] && [ -d "$mozilla_dir/$p" ] && echo "$mozilla_dir/$p" && return 0
    find "$mozilla_dir" -maxdepth 1 -type d -name "*.default*" 2>/dev/null | head -1
}

ensure_target_profile() {
    local existing=$(discover_gecko_profile "$1")
    [ -n "$existing" ] && echo "$existing" && return 0
    monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 8
    force_stop_package "$1"
    discover_gecko_profile "$1"
}

migrate_gecko_main() {
    log_init
    check_root; check_sqlite3
    check_package_exists "$SRC_PKG" || exit 1
    check_package_exists "$DST_PKG" || exit 1
    
    force_stop_package "$SRC_PKG"; force_stop_package "$DST_PKG"
    
    local src_prof=$(discover_gecko_profile "$SRC_PKG")
    [ -z "$src_prof" ] && exit 1
    check_disk_space "$src_prof" 50
    
    local dst_prof=$(ensure_target_profile "$DST_PKG")
    [ -z "$dst_prof" ] && exit 1
    
    backup_profile "$dst_prof" "target_original"
    save_rollback_manifest "$DST_PKG" "GECKO" "$dst_prof"
    
    # Core Data
    log_phase "2" "CEKIRDEK VERI"
    for f in places.sqlite favicons.sqlite logins.json key4.db formhistory.sqlite cookies.sqlite cert9.db permissions.sqlite content-prefs.sqlite; do
        safe_copy_file "$src_prof/$f" "$dst_prof/$f" "$f"
        [ -f "$src_prof/$f-wal" ] && cp -f "$src_prof/$f-wal" "$dst_prof/$f-wal"
    done
    
    # Extensions
    log_phase "3" "EKLENTILER"
    safe_copy_dir "$src_prof/extensions" "$dst_prof/extensions" "XPIs"
    safe_copy_file "$src_prof/extensions.json" "$dst_prof/extensions.json" "Metadata"
    sed -i "s|$SRC_PKG|$DST_PKG|g" "$dst_prof/extensions.json"
    
    # Cleanup
    log_phase "4" "TEMIZLIK"
    rm -f "$dst_prof/addonStartup.json.lz4" "$dst_prof/sessionstore.jsonlz4"
    local uid=$(get_package_uid "$DST_PKG")
    [ -n "$uid" ] && fix_ownership_recursive "/data/data/$DST_PKG/files/mozilla" "$uid"
    
    log_ok "Gecko gocu TAMAMLANDI!"
}

migrate_gecko_main
