#!/system/bin/sh
# ============================================================
#  chromium_migrate.sh — Chromium goc scripti (v2)
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -z "$SCRIPT_DIR" ]; then
    SCRIPT_DIR="/data/local/tmp/browser_migrator/scripts"
fi
. "${SCRIPT_DIR}/common.sh"

SRC="$1"
DST="$2"

# ============================================================
#  DİZİN KEŞFİ
# ============================================================
find_chromium_base() {
    local pkg="$1"
    for d in app_chrome app_chromium app_brave app_vivaldi; do
        if [ -d "/data/data/${pkg}/${d}" ]; then
            echo "/data/data/${pkg}/${d}"
            return 0
        fi
    done
    echo ""
    return 1
}

find_chromium_profile() {
    local base="$1"
    if [ -z "$base" ]; then echo ""; return 1; fi
    if [ -d "${base}/Default" ]; then
        echo "${base}/Default"
        return 0
    fi
    # History iceren ilk dizin
    for d in "${base}"/*/; do
        if [ -f "${d}History" ]; then
            echo "${d%/}"
            return 0
        fi
    done
    # Ilk alt dizin
    for d in "${base}"/*/; do
        if [ -d "$d" ]; then
            echo "${d%/}"
            return 0
        fi
    done
    echo ""
    return 1
}

ensure_chromium_profile() {
    local pkg="$1"
    local base
    base=$(find_chromium_base "$pkg")
    if [ -n "$base" ]; then
        local p
        p=$(find_chromium_profile "$base")
        if [ -n "$p" ] && [ -d "$p" ]; then
            echo "$p"
            return 0
        fi
    fi

    log_info "Hedef profil yok, tarayici baslatiliyor..."
    local intent
    intent=$(cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
    if [ -n "$intent" ]; then
        am start -n "$intent" >/dev/null 2>&1
    else
        monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    fi
    sleep 8
    stop_pkg "$pkg"
    sleep 2

    base=$(find_chromium_base "$pkg")
    if [ -z "$base" ]; then
        log_error "Chromium base bulunamadi: $pkg"
        echo ""; return 1
    fi
    local p
    p=$(find_chromium_profile "$base")
    echo "$p"
}

# ============================================================
#  ANA AKIS
# ============================================================
main() {
    log_init

    log_info "============================================"
    log_info "  Chromium Goc — v2"
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

    local src_base
    src_base=$(find_chromium_base "$SRC")
    if [ -z "$src_base" ]; then
        log_error "Kaynak base bulunamadi!"
        ls -la "/data/data/$SRC/" 2>/dev/null | while IFS= read -r l; do log_info "  $l"; done
        exit 1
    fi
    log_ok "Kaynak base: $src_base"

    local src_profile
    src_profile=$(find_chromium_profile "$src_base")
    if [ -z "$src_profile" ]; then
        log_error "Kaynak profil bulunamadi!"
        exit 1
    fi
    log_ok "Kaynak profil: $src_profile"

    check_disk "$src_profile"

    local dst_profile
    dst_profile=$(ensure_chromium_profile "$DST")
    local dst_base
    dst_base=$(find_chromium_base "$DST")

    if [ -z "$dst_profile" ] || [ -z "$dst_base" ]; then
        log_error "Hedef hazirlanamaadi!"
        exit 1
    fi
    log_ok "Hedef base: $dst_base"
    log_ok "Hedef profil: $dst_profile"

    # Yedekle
    mkdir -p "${BACKUP_DIR}/target_original_profile" 2>/dev/null
    cp -rf "$dst_profile/." "${BACKUP_DIR}/target_original_profile/" 2>/dev/null
    save_manifest "$DST" "CHROMIUM" "$dst_profile"

    stop_pkg "$DST"
    sleep 1

    # ---- FAZA 1: Veritabanlari ----
    log_info "FAZA 1: VERITABANI GOCU"

    # Preferences kopyala (yamalamadan once)
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

    # ---- FAZA 2: Eklentiler ----
    log_info "FAZA 2: EKLENTI GOCU"

    safe_cp "${src_profile}/Extensions"               "${dst_profile}/Extensions" "Extensions/"
    safe_cp "${src_profile}/Local Extension Settings"  "${dst_profile}/Local Extension Settings" "Local Extension Settings/"
    safe_cp "${src_profile}/Extension State"           "${dst_profile}/Extension State" "Extension State/"
    safe_cp "${src_profile}/Extension Rules"           "${dst_profile}/Extension Rules" "Extension Rules/"

    # Eklenti IndexedDB
    if [ -d "${src_profile}/IndexedDB" ]; then
        for idb in "${src_profile}/IndexedDB/chrome-extension_"*; do
            if [ -d "$idb" ]; then
                local n
                n=$(basename "$idb")
                safe_cp "$idb" "${dst_profile}/IndexedDB/${n}" "ext-idb: $n"
            fi
        done
    fi

    # ---- FAZA 3: JSON Yamalama ----
    log_info "FAZA 3: JSON YAMALAMA"

    local src_bn dst_bn
    src_bn=$(basename "$src_base")
    dst_bn=$(basename "$dst_base")

    # Local State
    if [ ! -f "${dst_base}/Local State" ] && [ -f "${src_base}/Local State" ]; then
        safe_cp "${src_base}/Local State" "${dst_base}/Local State" "Local State"
    fi

    for jf in "${dst_base}/Local State" "${dst_profile}/Preferences" "${dst_profile}/Secure Preferences"; do
        if [ -f "$jf" ] && grep -q "$SRC" "$jf" 2>/dev/null; then
            cp -f "$jf" "${jf}.bak" 2>/dev/null
            sed -i "s|/data/data/${SRC}/|/data/data/${DST}/|g" "$jf" 2>/dev/null
            sed -i "s|${SRC}|${DST}|g" "$jf" 2>/dev/null
            if [ "$src_bn" != "$dst_bn" ]; then
                sed -i "s|${src_bn}|${dst_bn}|g" "$jf" 2>/dev/null
            fi
            log_ok "Yamalandi: $(basename "$jf")"
        fi
    done

    # Secure Preferences HMAC temizle
    if [ -f "${dst_profile}/Secure Preferences" ]; then
        sed -i '/"super_mac"/d' "${dst_profile}/Secure Preferences" 2>/dev/null
        sed -i 's/"mac":"[^"]*"/"mac":""/g' "${dst_profile}/Secure Preferences" 2>/dev/null
        log_ok "Secure Prefs HMAC notralize edildi"
    fi

    # ---- FAZA 4: SQLite Yamalama ----
    log_info "FAZA 4: SQLite YAMALAMA"

    if [ -n "$SQLITE3_BIN" ]; then
        for db_name in History Cookies "Web Data" "Login Data" Favicons; do
            local db="${dst_profile}/${db_name}"
            if [ ! -f "$db" ]; then continue; fi

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
            if [ $patched -gt 0 ]; then
                log_ok "SQLite yamalandi: $db_name ($patched)"
            fi
        done
    else
        log_warn "sqlite3 yok, SQLite yamalama atlandi"
    fi

    # ---- FAZA 5: Web Depolama ----
    log_info "FAZA 5: WEB DEPOLAMA"

    safe_cp "${src_profile}/Local Storage"   "${dst_profile}/Local Storage" "Local Storage/"
    safe_cp "${src_profile}/IndexedDB"       "${dst_profile}/IndexedDB" "IndexedDB/"
    safe_cp "${src_profile}/databases"       "${dst_profile}/databases" "WebSQL/"
    safe_cp "${src_profile}/Session Storage" "${dst_profile}/Session Storage" "Session Storage/"

    # ---- FAZA 6: Temizlik ----
    log_info "FAZA 6: TEMIZLIK"

    for cd in GPUCache "Code Cache" Cache; do
        rm -rf "${dst_profile}/${cd}" 2>/dev/null
    done
    rm -rf "/data/data/${DST}/cache" 2>/dev/null

    local uid
    uid=$(get_uid "$DST")
    if [ -n "$uid" ]; then
        fix_perms "$dst_base" "$uid"
    else
        log_error "UID alinamadi!"
    fi

    stop_pkg "$DST"

    log_info "============================================"
    log_ok   "CHROMIUM GOCU TAMAMLANDI!"
    log_warn "Sifreler Keystore'a bagli — farkli UID'de cozulemeyebilir"
    log_info "============================================"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Kullanim: $0 <kaynak_paket> <hedef_paket>"
    exit 1
fi

main
