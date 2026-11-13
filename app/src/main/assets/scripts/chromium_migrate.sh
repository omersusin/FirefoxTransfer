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
# $3 = account (unused for Chromium), $4 = extensions, $5 = open tabs
MIGRATE_ACCOUNT="${3:-1}"
MIGRATE_EXTENSIONS="${4:-1}"
MIGRATE_TABS="${5:-1}"

G_BASE=""
G_PROFILE=""

# ============================================================
#  DIRECTORY DISCOVERY
# ============================================================
find_chromium_base() {
    local pkg="$1"
    G_BASE=""

    local dd
    dd=$(find_data_dir "$pkg")
    if [ -z "$dd" ]; then
        log_error "Data directory not found: $pkg"
        return 1
    fi

    for d in \
        app_chrome app_chromium app_brave app_vivaldi app_edge \
        app_sbrowser app_kiwi app_opera app_ddg app_yandex app_mi app_quark \
        app_barebrowser app_chrome_beta \
    ; do
        if [ -d "${dd}/${d}" ]; then
            G_BASE="${dd}/${d}"
            log_ok "Chromium base: $G_BASE"
            return 0
        fi
    done

    # Profile directly under the data dir (some Chromium forks do this).
    if [ -d "${dd}/Default" ]; then
        G_BASE="$dd"
        log_ok "Chromium base: $G_BASE"
        return 0
    fi

    # Broad search: look for real profile files, not only Bookmarks (a fresh
    # Chrome install creates History/Preferences before it ever creates Bookmarks).
    local found
    found=$(find "$dd" -maxdepth 4 -type f \
        \( -name "History" -o -name "Bookmarks" -o -name "Preferences" \) \
        2>/dev/null | head -1)
    if [ -n "$found" ]; then
        G_BASE=$(dirname "$(dirname "$found")")
        log_ok "Chromium base (search): $G_BASE"
        return 0
    fi

    log_error "Chromium base not found: $pkg"
    log_info "--- Directory content ---"
    ls -la "$dd/" 2>/dev/null | while IFS= read -r l; do log_info "  $l"; done
    return 1
}

find_chromium_profile() {
    local base="$1"
    G_PROFILE=""
    if [ -z "$base" ]; then return 1; fi

    # Pick the profile that contains the most real data. Chrome can have
    # Default, Profile 1, Profile 2, ... and "Default" is not always the one
    # the user actually uses.
    local best="" best_score=-1 d f score
    for d in "$base"/Default "$base"/Profile*; do
        [ -d "$d" ] || continue
        score=0
        for f in History Bookmarks Preferences "Login Data" "Web Data"; do
            if [ -f "$d/$f" ]; then
                score=$((score + $(stat -c '%s' "$d/$f" 2>/dev/null || echo 0)))
            fi
        done
        if [ "$score" -gt "$best_score" ]; then
            best="$d"
            best_score="$score"
        fi
    done

    if [ -z "$best" ] && [ -d "${base}/Default" ]; then
        best="${base}/Default"
    fi

    if [ -n "$best" ]; then
        G_PROFILE="$best"
        log_ok "Chromium profile: $G_PROFILE"
        return 0
    fi

    return 1
}

guess_chromium_base() {
    case "$1" in
        *brave*) echo "app_brave" ;;
        *vivaldi*) echo "app_vivaldi" ;;
        *emmx*|*edge*) echo "app_edge" ;;
        *sbrowser*) echo "app_sbrowser" ;;
        *kiwi*) echo "app_kiwi" ;;
        *opera*) echo "app_opera" ;;
        *duckduckgo*) echo "app_ddg" ;;
        *barebrowser*) echo "app_barebrowser" ;;
        *) echo "app_chrome" ;;
    esac
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

    log_info "Target profile missing, starting browser..."
    local attempt=1
    while [ "$attempt" -le 3 ]; do
        local intent
        intent=$(cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
        if [ -n "$intent" ]; then
            am start -n "$intent" >/dev/null 2>&1
        else
            log_info "Starting with monkey (attempt $attempt)"
            monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        fi
        sleep 8
        stop_pkg "$pkg"
        sleep 2

        find_chromium_base "$pkg"
        if [ -n "$G_BASE" ]; then
            find_chromium_profile "$G_BASE"
            if [ -n "$G_PROFILE" ]; then
                log_ok "Target profile created by browser: $G_PROFILE"
                return 0
            fi
        fi
        attempt=$((attempt + 1))
    done

    # The browser may be blocked from starting in the background (Android 10+).
    # A conventional empty profile is enough: the migration fills it and the
    # browser initialises the rest on its next launch.
    local dd
    dd=$(find_data_dir "$pkg")
    if [ -n "$dd" ]; then
        local base="${dd}/$(guess_chromium_base "$pkg")"
        mkdir -p "${base}/Default" 2>/dev/null
        G_BASE="$base"
        G_PROFILE="${base}/Default"
        log_warn "Browser did not create a profile; using empty profile: $G_PROFILE"
        return 0
    fi

    log_error "Chromium base still not found: $pkg"
    G_PROFILE=""
    return 1
}

# ============================================================
#  MAIN FLOW
# ============================================================
main() {
    log_init

    log_info "============================================"
    log_info "  Chromium Migration v4"
    log_info "  Source: $SRC"
    log_info "  Target: $DST"
    log_info "  Options: extensions=$MIGRATE_EXTENSIONS tabs=$MIGRATE_TABS"
    log_info "============================================"

    check_root
    check_sqlite3
    check_package "$SRC" || exit 1
    check_package "$DST" || exit 1

    stop_pkg "$SRC"
    stop_pkg "$DST"

    # ---- PHASE 0 ----
    log_info "PHASE 0: DISCOVERY"

    find_chromium_base "$SRC"
    local src_base="$G_BASE"
    if [ -z "$src_base" ]; then exit 1; fi

    find_chromium_profile "$src_base"
    local src_profile="$G_PROFILE"
    if [ -z "$src_profile" ]; then
        log_error "Source profile not found!"
        exit 1
    fi
    log_ok "Source profile: $src_profile"

    check_disk "$src_profile"

    ensure_chromium_profile "$DST"
    local dst_profile="$G_PROFILE"
    local dst_base="$G_BASE"
    local dst_dd
    dst_dd=$(find_data_dir "$DST")

    if [ -z "$dst_profile" ] || [ -z "$dst_base" ]; then
        log_error "Target preparation failed!"
        exit 1
    fi
    log_ok "Target profile: $dst_profile"

    # Backup
    mkdir -p "${BACKUP_DIR}/target_original_profile" 2>/dev/null
    cp -rf "$dst_profile/." "${BACKUP_DIR}/target_original_profile/" 2>/dev/null
    mkdir -p "${BACKUP_DIR}/target_local_state" 2>/dev/null
    cp -f "${dst_base}/Local State" "${BACKUP_DIR}/target_local_state/Local State" 2>/dev/null || true
    save_manifest "$DST" "CHROMIUM" "$dst_profile" "$dst_dd" "$dst_base" "profile"

    stop_pkg "$DST"
    sleep 1

    # ---- PHASE 1 ----
    log_info "PHASE 1: DATABASE MIGRATION"

    safe_cp "${src_profile}/Preferences" "${dst_profile}/Preferences" "Preferences"
    safe_cp "${src_profile}/Secure Preferences" "${dst_profile}/Secure Preferences" "Secure Preferences"

    # Chrome moved cookies to Default/Network/Cookies years ago. Copy both the
    # modern and legacy locations so newer and older forks both work.
    for f in "Network/Cookies" "Cookies"; do
        copy_db_with_sidecars "${src_profile}/${f}" "${dst_profile}/${f}" "$f"
    done

    safe_cp "${src_profile}/Bookmarks" "${dst_profile}/Bookmarks" "Bookmarks"

    for f in \
        History "Login Data" "Web Data" \
        Favicons "Top Sites" Shortcuts \
        "Network Action Predictor" \
    ; do
        copy_db_with_sidecars "${src_profile}/${f}" "${dst_profile}/${f}" "$f"
    done

    # Chrome writes Bookmarks.bak on the first edit. If Bookmarks is absent
    # (fresh sync, interrupted write), the .bak is still a valid source.
    if [ ! -f "${dst_profile}/Bookmarks" ] && [ -f "${src_profile}/Bookmarks.bak" ]; then
        safe_cp "${src_profile}/Bookmarks.bak" "${dst_profile}/Bookmarks" "Bookmarks (from .bak)"
    fi

    # A stale target .bak can make Chromium discard the newly written
    # Bookmarks file on next launch. The original is already in BACKUP_DIR.
    rm -f "${dst_profile}/Bookmarks.bak" "${dst_profile}/Bookmarks.tmp" 2>/dev/null

    # ---- PHASE 2 ----
    log_info "PHASE 2: OPEN-TAB / SESSION MIGRATION"

    if [ "$MIGRATE_TABS" = "1" ]; then
    # Chromium keeps the live session in Sessions/Session_* (modern) or in
    # Current Session/Current Tabs (older forks). Copying these files is safe
    # only engine-to-engine; cross-engine tab transfer is handled in Kotlin by
    # reading the source session and opening its URLs in the target.
    if [ -d "${src_profile}/Sessions" ]; then
        rm -rf "${dst_profile}/Sessions" 2>/dev/null
        safe_cp "${src_profile}/Sessions" "${dst_profile}/Sessions" "Sessions/"
    fi
    for sf in "Current Session" "Current Tabs" "Last Session" "Last Tabs"; do
        if [ -f "${src_profile}/${sf}" ]; then
            safe_cp "${src_profile}/${sf}" "${dst_profile}/${sf}" "$sf"
        fi
    done
    else
        log_info "Open-tab migration disabled; target tabs preserved"
    fi

    # ---- PHASE 3 ----
    log_info "PHASE 3: EXTENSION MIGRATION"

    if [ "$MIGRATE_EXTENSIONS" = "1" ]; then
    safe_cp "${src_profile}/Extensions" "${dst_profile}/Extensions" "Extensions/"
    safe_cp "${src_profile}/Local Extension Settings" "${dst_profile}/Local Extension Settings" "Local Extension Settings/"
    safe_cp "${src_profile}/Sync Extension Settings" "${dst_profile}/Sync Extension Settings" "Sync Extension Settings/"
    safe_cp "${src_profile}/Extension State" "${dst_profile}/Extension State" "Extension State/"
    safe_cp "${src_profile}/Extension Rules" "${dst_profile}/Extension Rules" "Extension Rules/"

    if [ -d "${src_profile}/IndexedDB" ]; then
        for idb in "${src_profile}/IndexedDB/chrome-extension_"*; do
            if [ -d "$idb" ]; then
                safe_cp "$idb" "${dst_profile}/IndexedDB/$(basename "$idb")" "ext-idb"
            fi
        done
    fi
    else
        log_info "Extension migration disabled; target extensions preserved"
    fi

    # ---- PHASE 4 ----
    log_info "PHASE 4: JSON PATCHING"

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
            log_ok "Patched: $(basename "$jf")"
        fi
    done

    if [ -f "${dst_profile}/Secure Preferences" ]; then
        # Keep the file valid JSON. Deleting lines would blank a minified file;
        # an empty HMAC makes Chromium reset its protected preferences safely.
        sed -i 's/"super_mac":"[^"]*"/"super_mac":""/g' "${dst_profile}/Secure Preferences" 2>/dev/null
        sed -i 's/"mac":"[^"]*"/"mac":""/g' "${dst_profile}/Secure Preferences" 2>/dev/null
        log_ok "Secure Prefs HMAC cleaned"
    fi

    # ---- PHASE 4 ----
    log_info "PHASE 4: SQLite PATCHING"

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
        log_info "sqlite3 not found, advanced SQLite path rewriting skipped (data is still copied)"
    fi

    # ---- PHASE 6 ----
    log_info "PHASE 6: WEB STORAGE"

    safe_cp "${src_profile}/Local Storage" "${dst_profile}/Local Storage" "Local Storage/"
    safe_cp "${src_profile}/IndexedDB" "${dst_profile}/IndexedDB" "IndexedDB/"
    safe_cp "${src_profile}/databases" "${dst_profile}/databases" "WebSQL/"
    safe_cp "${src_profile}/Session Storage" "${dst_profile}/Session Storage" "Session Storage/"

    # ---- PHASE 7 ----
    log_info "PHASE 7: CLEANUP"

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
        log_error "Could not get UID!"
    fi

    stop_pkg "$DST"

    log_info "============================================"
    log_ok   "CHROMIUM MIGRATION COMPLETED!"
    log_info "============================================"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Usage: $0 <source> <target>" >&2
    exit 1
fi

main
