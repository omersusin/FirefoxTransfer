#!/system/bin/sh
# ============================================================
#  chromium_migrate.sh v3
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -z "$SCRIPT_DIR" ]; then
    SCRIPT_DIR="/data/local/tmp/browser_migrator/scripts"
fi
. "${SCRIPT_DIR}/common.sh"

SRC="$1"
DST="$2"

G_BASE=""
G_PROFILE=""

# ============================================================
#  DİZİN KEŞFİ
# ============================================================
find_chromium_base() {
    local pkg="$1"
    G_BASE=""

    local dd
    dd=$(find_data_dir "$pkg")
    if [ -z "$dd" ]; then
        log_error "Veri dizini bulunamadi: $pkg"
        return 1
    fi

    for d in app_chrome app_chromium app_brave app_vivaldi; do
        if [ -d "${dd}/${d}" ]; then
            G_BASE="${dd}/${d}"
            log_ok "Chromium base: $G_BASE"
            return 0
        fi
    done

    # Genis arama: Bookmarks veya History dosyasi ara
    local found
    found=$(find "$dd" -name "Bookmarks" -type f 2>/dev/null | head -1)
    if [ -n "$found" ]; then
        G_BASE=$(dirname "$(dirname "$found")")
        log_ok "Chromium base (arama): $G_BASE"
        return 0
    fi

    log_error "Chromium base bulunamadi: $pkg"
    log_info "--- Dizin icerigi ---"
    ls -la "$dd/" 2>/dev/null | while IFS= read -r l; do log_info "  $l"; done
    return 1
}

find_chromium_profile() {
    local base="$1"
    G_PROFILE=""
    if [ -z "$base" ]; then return 1; fi

    if [ -d "${base}/Default" ]; then
        G_PROFILE="${base}/Default"
        return 0
    fi

    for d in "${base}"/*/; do
        [ ! -d "$d" ] && continue
        if [ -f "${d}History" ] || [ -f "${d}Bookmarks" ]; then
            G_PROFILE="${d%/}"
            return 0
        fi
    done

    for d in "${base}"/*/; do
        if [ -d "$d" ]; then
            G_PROFILE="${d%/}"
            return 0
        fi
    done

    return 1
}

ensure_chromium_profile() {
    local pkg="$1"

    find_chromium_base "$pkg"
    if [ -n "$G_BASE" ]; then
        find_chromium_profile "$G_BASE"
        if [ -n "$G_PROFILE" ]; then
            return 0
        fi
    fi

    log_info "Hedef profil yok, tarayici baslatiliyor..."
    local intent
    intent=$(cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
    if [ -n "$intent" ]; then
        am start -n "$intent" >/dev/null 2>&1
    else
        log_info "monkey ile baslatiliyor"
        monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    fi
    sleep 10
    stop_pkg "$pkg"
    sleep 2

    find_chromium_base "$pkg"
    if [ -z "$G_BASE" ]; then
        log_error "Chromium base hala bulunamadi: $pkg"
        G_PROFILE=""
        return 1
    fi
    find_chromium_profile "$G_BASE"
    return 0
}

# ============================================================
#  ANA AKIS
# ============================================================
main() {
    log_init

    log_info "============================================"
    log_info "  Chromium Goc v3"
    log_info "  Kaynak: $SRC"
    log_info "  Hedef:  $DST"
    log_info "============================================"

    check_root
    check_sqlite3
    check_package "$SRC" || exit 1
    check_package "$DST" || exit 1

    stop_pkg "$SRC"
    stop_pkg "$DST"

    # ---- FAZA 0 ----
    log_info "FAZA 0: KESIF"

    find_chromium_base "$SRC"
    local src_base="$G_BASE"
    if [ -z "$src_base" ]; then exit 1; fi

    find_chromium_profile "$src_base"
    local src_profile="$G_PROFILE"
    if [ -z "$src_profile" ]; then
        log_error "Kaynak profil bulunamadi!"
        exit 1
    fi
    log_ok "Kaynak profil: $src_profile"

    check_disk "$src_profile"

    ensure_chromium_profile "$DST"
    local dst_profile="$G_PROFILE"
    local dst_base="$G_BASE"

    if [ -z "$dst_profile" ] || [ -z "$dst_base" ]; then
        log_error "Hedef hazirlanamaadi!"
        exit 1
    fi
    log_ok "Hedef profil: $dst_profile"

    # Yedek
    mkdir -p "${BACKUP_DIR}/target_original_profile" 2>/dev/null
    cp -rf "$dst_profile/." "${BACKUP_DIR}/target_original_profile/" 2>/dev/null
    save_manifest "$DST" "CHROMIUM" "$dst_profile"

    stop_pkg "$DST"
    sleep 1

    # ---- FAZA 1 ----
    log_info "FAZA 1: VERITABANI GOCU"

    safe_cp "${src_profile}/Preferences" "${dst_profile}/Preferences" "Preferences"
    safe_cp "${src_profile}/Secure Preferences" "${dst_profile}/Secure Preferences" "Secure Preferences"

    for f in \
        Bookmarks History "Login Data" "Web Data" \
        Cookies Favicons "Top Sites" Shortcuts \
        "Network Action Predictor" \
    ; do
        safe_cp "${src_profile}/${f}" "${dst_profile}/${f}" "$f"
        for sfx in "-journal" "-wal" "-shm"; do
            safe_cp "${src_profile}/${f}${sfx}" "${dst_profile}/${f}${sfx}" "${f}${sfx}"
        done
    done

    # ---- FAZA 2 ----
    log_info "FAZA 2: EKLENTI GOCU"

    safe_cp "${src_profile}/Extensions" "${dst_profile}/Extensions" "Extensions/"
    safe_cp "${src_profile}/Local Extension Settings" "${dst_profile}/Local Extension Settings" "Local Extension Settings/"
    safe_cp "${src_profile}/Extension State" "${dst_profile}/Extension State" "Extension State/"
    safe_cp "${src_profile}/Extension Rules" "${dst_profile}/Extension Rules" "Extension Rules/"

    if [ -d "${src_profile}/IndexedDB" ]; then
        for idb in "${src_profile}/IndexedDB/chrome-extension_"*; do
            if [ -d "$idb" ]; then
                safe_cp "$idb" "${dst_profile}/IndexedDB/$(basename "$idb")" "ext-idb"
            fi
        done
    fi

    # ---- FAZA 3 ----
    log_info "FAZA 3: JSON YAMALAMA"

    local src_bn dst_bn
    src_bn=$(basename "$src_base")
    dst_bn=$(basename "$dst_base")

    if [ ! -f "${dst_base}/Local State" ] && [ -f "${src_base}/Local State" ]; then
        safe_cp "${src_base}/Local State" "${dst_base}/Local State" "Local State"
    fi

    for jf in "${dst_base}/Local State" "${dst_profile}/Preferences" "${dst_profile}/Secure Preferences"; do
        if [ -f "$jf" ] && grep -q "$SRC" "$jf" 2>/dev/null; then
            cp -f "$jf" "${jf}.bak" 2>/dev/null
            sed -i "s|/data/data/${SRC}/|/data/data/${DST}/|g" "$jf" 2>/dev/null
            sed -i "s|/data/user/0/${SRC}/|/data/user/0/${DST}/|g" "$jf" 2>/dev/null
            sed -i "s|${SRC}|${DST}|g" "$jf" 2>/dev/null
            if [ "$src_bn" != "$dst_bn" ]; then
                sed -i "s|${src_bn}|${dst_bn}|g" "$jf" 2>/dev/null
            fi
            log_ok "Yamalandi: $(basename "$jf")"
        fi
    done

    if [ -f "${dst_profile}/Secure Preferences" ]; then
        sed -i '/"super_mac"/d' "${dst_profile}/Secure Preferences" 2>/dev/null
        sed -i 's/"mac":"[^"]*"/"mac":""/g' "${dst_profile}/Secure Preferences" 2>/dev/null
        log_ok "Secure Prefs HMAC temizlendi"
    fi

    # ---- FAZA 4 ----
    log_info "FAZA 4: SQLite YAMALAMA"

    if [ -n "$SQLITE3_BIN" ]; then
        for db_name in History Cookies "Web Data" "Login Data" Favicons; do
            local db="${dst_profile}/${db_name}"
            [ ! -f "$db" ] && continue

            local tables
            tables=$($SQLITE3_BIN "$db" "SELECT name FROM sqlite_master WHERE type='table';" 2>/dev/null)
            local patched=0

            for table in $tables; do
                local cols
                cols=$($SQLITE3_BIN "$db" "PRAGMA table_info([${table}]);" 2>/dev/null | awk -F'|' '{print $2}')
                for col in $cols; do
                    local cnt
                    cnt=$($SQLITE3_BIN "$db" "SELECT COUNT(*) FROM [${table}] WHERE [${col}] LIKE '%${SRC}%';" 2>/dev/null)
                    if [ -n "$cnt" ] && [ "$cnt" -gt 0 ] 2>/dev/null; then
                        $SQLITE3_BIN "$db" "UPDATE [${table}] SET [${col}]=REPLACE([${col}],'${SRC}','${DST}') WHERE [${col}] LIKE '%${SRC}%';" 2>/dev/null
                        patched=$((patched + cnt))
                    fi
                done
            done
            [ $patched -gt 0 ] && log_ok "SQLite: $db_name ($patched)"
        done
    else
        log_warn "sqlite3 yok, SQLite yamalama atlandi"
    fi

    # ---- FAZA 5 ----
    log_info "FAZA 5: WEB DEPOLAMA"

    safe_cp "${src_profile}/Local Storage" "${dst_profile}/Local Storage" "Local Storage/"
    safe_cp "${src_profile}/IndexedDB" "${dst_profile}/IndexedDB" "IndexedDB/"
    safe_cp "${src_profile}/databases" "${dst_profile}/databases" "WebSQL/"
    safe_cp "${src_profile}/Session Storage" "${dst_profile}/Session Storage" "Session Storage/"

    # ---- FAZA 6 ----
    log_info "FAZA 6: TEMIZLIK"

    for cd in GPUCache "Code Cache" Cache; do
        rm -rf "${dst_profile}/${cd}" 2>/dev/null
    done

    local dst_dd
    dst_dd=$(find_data_dir "$DST")
    [ -n "$dst_dd" ] && rm -rf "${dst_dd}/cache" 2>/dev/null

    local uid=$(get_uid "$DST")
    if [ -n "$uid" ]; then
        fix_perms "$dst_base" "$uid"
    else
        log_error "UID alinamadi!"
    fi

    stop_pkg "$DST"

    log_info "============================================"
    log_ok   "CHROMIUM GOCU TAMAMLANDI!"
    log_info "============================================"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Kullanim: $0 <kaynak> <hedef>" >&2
    exit 1
fi

main
