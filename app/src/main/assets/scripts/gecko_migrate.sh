#!/system/bin/sh
# ============================================================
#  gecko_migrate.sh v5
#  Fenix fix: DBs and tabs under files/
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -z "$SCRIPT_DIR" ]; then
    SCRIPT_DIR="/data/local/tmp/browser_migrator/scripts"
fi
. "${SCRIPT_DIR}/common.sh"

SRC="$1"
DST="$2"

G_PROFILE=""
G_DATA_DIR=""

# ============================================================
#  DATA DIRECTORY + PROFILE DISCOVERY
# ============================================================
resolve_data_dir() {
    local pkg="$1"
    G_DATA_DIR=""
    G_DATA_DIR=$(find_data_dir "$pkg")
    if [ -z "$G_DATA_DIR" ]; then
        log_error "Data directory not found: $pkg"
        return 1
    fi
    log_ok "Data directory: $G_DATA_DIR"
    return 0
}

find_gecko_profile() {
    local pkg="$1"
    G_PROFILE=""

    resolve_data_dir "$pkg"
    if [ -z "$G_DATA_DIR" ]; then return 1; fi

    local dd="$G_DATA_DIR"

    # Debug
    log_info "--- $dd/files/ content ---"
    if [ -d "$dd/files" ]; then
        ls -la "$dd/files/" 2>/dev/null | while IFS= read -r line; do
            log_info "  $line"
        done
    fi

    # 1. Search for profile under files/mozilla/
    local mozilla_dir="$dd/files/mozilla"
    if [ -d "$mozilla_dir" ]; then
        log_info "files/mozilla/ found"

        if [ -f "$mozilla_dir/profiles.ini" ]; then
            local ini_path
            ini_path=$(grep "^Path=" "$mozilla_dir/profiles.ini" 2>/dev/null | head -1 | cut -d= -f2 | tr -d '\r\n ')
            if [ -n "$ini_path" ] && [ -d "$mozilla_dir/$ini_path" ]; then
                G_PROFILE="$mozilla_dir/$ini_path"
                log_ok "Profile (profiles.ini): $G_PROFILE"
                return 0
            fi
        fi

        for d in "$mozilla_dir"/*/; do
            [ ! -d "$d" ] && continue
            local dname=$(basename "$d")
            case "$dname" in
                *.default*|*.svc*|*.release*|*.nightly*)
                    G_PROFILE="${d%/}"
                    log_ok "Profile (glob): $G_PROFILE"
                    return 0
                    ;;
            esac
        done

        for d in "$mozilla_dir"/*/; do
            if [ -d "$d" ]; then
                G_PROFILE="${d%/}"
                log_warn "Profile (first directory): $G_PROFILE"
                return 0
            fi
        done
    fi

    # 2. Broad search
    if [ -d "$dd/files" ]; then
        local found
        found=$(find "$dd/files" -name "places.sqlite" -type f 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            G_PROFILE=$(dirname "$found")
            log_ok "Profile (find): $G_PROFILE"
            return 0
        fi
    fi

    log_error "PROFILE NOT FOUND: $pkg"
    return 1
}

ensure_dst_profile() {
    local pkg="$1"

    find_gecko_profile "$pkg"
    if [ -n "$G_PROFILE" ]; then
        log_ok "Target profile exists: $G_PROFILE"
        return 0
    fi

    log_info "Target profile missing, starting browser..."
    local intent=$(cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
    if [ -n "$intent" ]; then
        am start -n "$intent" >/dev/null 2>&1
    else
        monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    fi
    sleep 10
    stop_pkg "$pkg"
    sleep 2

    find_gecko_profile "$pkg"
    if [ -n "$G_PROFILE" ]; then
        log_ok "Clean profile created: $G_PROFILE"
        return 0
    fi

    log_error "Could not create profile: $pkg"
    return 1
}

# ============================================================
#  MAIN FLOW
# ============================================================
main() {
    log_init

    log_info "============================================"
    log_info "  Gecko Migration v5 (Fenix compatible)"
    log_info "  Source: $SRC"
    log_info "  Target: $DST"
    log_info "============================================"

    check_root
    check_sqlite3
    check_package "$SRC" || exit 1
    check_package "$DST" || exit 1

    stop_pkg "$SRC"
    stop_pkg "$DST"

    # ==== PHASE 0: Discovery ====
    log_info "PHASE 0: DISCOVERY"

    find_gecko_profile "$SRC"
    local src_profile="$G_PROFILE"
    local src_dd="$G_DATA_DIR"
    local src_files="$src_dd/files"

    if [ -z "$src_profile" ]; then
        log_error "SOURCE PROFILE NOT FOUND!"
        exit 1
    fi
    log_ok "Source profile: $src_profile"
    log_ok "Source files: $src_files"
    local src_name=$(basename "$src_profile")

    check_disk "$src_files"

    # ==== PHASE 1: Target Profile ====
    log_info "PHASE 1: TARGET PROFILE"

    ensure_dst_profile "$DST"
    local dst_profile="$G_PROFILE"
    local dst_dd="$G_DATA_DIR"
    local dst_files="$dst_dd/files"

    if [ -z "$dst_profile" ]; then
        log_error "TARGET PROFILE NOT READY!"
        exit 1
    fi
    log_ok "Target profile: $dst_profile"
    log_ok "Target files: $dst_files"
    local dst_name=$(basename "$dst_profile")

    # Backup
    log_info "Backing up target..."
    mkdir -p "${BACKUP_DIR}/target_files" 2>/dev/null
    cp -rf "$dst_files/." "${BACKUP_DIR}/target_files/" 2>/dev/null
    save_manifest "$DST" "GECKO" "$dst_profile"
    log_ok "Backup: $BACKUP_DIR"

    stop_pkg "$DST"
    sleep 1

    # ==== PHASE 2: Fenix files/ Level DBs ====
    log_info "PHASE 2: FILES-LEVEL DATA"

    # --- places.sqlite (History + Bookmarks) ---
    if [ -f "${src_files}/places.sqlite" ]; then
        log_info "places.sqlite in files/ (Fenix)"
        safe_cp "${src_files}/places.sqlite"     "${dst_files}/places.sqlite"     "places.sqlite (files/)"
        safe_cp "${src_files}/places.sqlite-wal"  "${dst_files}/places.sqlite-wal"  "places.sqlite-wal (files/)"
        safe_cp "${src_files}/places.sqlite-shm"  "${dst_files}/places.sqlite-shm"  "places.sqlite-shm (files/)"
    elif [ -f "${src_profile}/places.sqlite" ]; then
        log_info "places.sqlite in profile (classic)"
        safe_cp "${src_profile}/places.sqlite"     "${dst_profile}/places.sqlite"     "places.sqlite (profile)"
        safe_cp "${src_profile}/places.sqlite-wal"  "${dst_profile}/places.sqlite-wal"  "places.sqlite-wal (profile)"
        safe_cp "${src_profile}/places.sqlite-shm"  "${dst_profile}/places.sqlite-shm"  "places.sqlite-shm (profile)"
    fi

    # --- logins.json + key4.db (Passwords) ---
    if [ -f "${src_files}/logins.json" ]; then
        safe_cp "${src_files}/logins.json" "${dst_files}/logins.json" "logins.json (files/)"
    elif [ -f "${src_profile}/logins.json" ]; then
        safe_cp "${src_profile}/logins.json" "${dst_profile}/logins.json" "logins.json (profile)"
    fi

    if [ -f "${src_files}/key4.db" ]; then
        safe_cp "${src_files}/key4.db" "${dst_files}/key4.db" "key4.db (files/)"
    elif [ -f "${src_profile}/key4.db" ]; then
        safe_cp "${src_profile}/key4.db" "${dst_profile}/key4.db" "key4.db (profile)"
    fi

    # --- tabs.sqlite (Tabs) ---
    if [ -f "${src_files}/tabs.sqlite" ]; then
        safe_cp "${src_files}/tabs.sqlite"     "${dst_files}/tabs.sqlite"     "tabs.sqlite"
        safe_cp "${src_files}/tabs.sqlite-wal"  "${dst_files}/tabs.sqlite-wal"  "tabs.sqlite-wal"
        safe_cp "${src_files}/tabs.sqlite-shm"  "${dst_files}/tabs.sqlite-shm"  "tabs.sqlite-shm"
    fi

    # --- Session data (Fenix tab state) ---
    # SKIPPED — Fenix/Iceraven version incompatibility in JSON format
    # causes crashes. tabs.sqlite already carries tab data.
    # Target browser recreates session from tabs.sqlite on first launch.
    if [ -f "${src_files}/mozilla_components_session_storage_gecko.json" ]; then
        log_warn "Session JSON skipped (version incompatibility prevention)"
    fi

    # --- push.sqlite ---
    safe_cp "${src_files}/push.sqlite" "${dst_files}/push.sqlite" "push.sqlite"

    # --- Other files-level files ---
    for f in "nimbus_messages_metadata.json" "profileInstalled" "mozilla_components_service_mars_tiles.json"; do
        safe_cp "${src_files}/${f}" "${dst_files}/${f}" "$f"
    done

    # ==== PHASE 3: Profile-Level Data ====
    log_info "PHASE 3: PROFILE-LEVEL DATA"

    for f in \
        favicons.sqlite favicons.sqlite-wal favicons.sqlite-shm \
        formhistory.sqlite \
        cookies.sqlite cookies.sqlite-wal cookies.sqlite-shm \
        cert9.db permissions.sqlite content-prefs.sqlite \
        webappsstore.sqlite \
    ; do
        if [ -f "${src_profile}/${f}" ]; then
            safe_cp "${src_profile}/${f}" "${dst_profile}/${f}" "$f"
        fi
    done

    # ==== PHASE 4: Extensions ====
    log_info "PHASE 4: EXTENSION MIGRATION"

    safe_cp "${src_profile}/extensions" "${dst_profile}/extensions" "extensions/"
    safe_cp "${src_profile}/extensions.json" "${dst_profile}/extensions.json" "extensions.json"
    safe_cp "${src_profile}/extension-preferences.json" "${dst_profile}/extension-preferences.json" "extension-preferences.json"
    safe_cp "${src_profile}/browser-extension-data" "${dst_profile}/browser-extension-data" "browser-extension-data/"

    if [ -f "${src_profile}/storage-sync-v2.sqlite" ]; then
        safe_cp "${src_profile}/storage-sync-v2.sqlite" "${dst_profile}/storage-sync-v2.sqlite" "storage-sync (profile)"
    fi
    if [ -f "${src_files}/storage-sync-v2.sqlite" ]; then
        safe_cp "${src_files}/storage-sync-v2.sqlite" "${dst_files}/storage-sync-v2.sqlite" "storage-sync (files/)"
    fi

    # extensions.json path patching
    if [ -f "${dst_profile}/extensions.json" ]; then
        log_info "Patching extensions.json paths..."
        sed -i "s|${src_dd}/files/mozilla/${src_name}|${dst_dd}/files/mozilla/${dst_name}|g" "${dst_profile}/extensions.json" 2>/dev/null
        sed -i "s|${src_dd}/|${dst_dd}/|g" "${dst_profile}/extensions.json" 2>/dev/null
        sed -i "s|${SRC}|${DST}|g" "${dst_profile}/extensions.json" 2>/dev/null
        log_ok "extensions.json patched"
    fi

    # UUID synchronization
    log_info "UUID synchronization..."
    local src_prefs="${src_profile}/prefs.js"
    local dst_prefs="${dst_profile}/prefs.js"
    if [ -f "$src_prefs" ]; then
        [ ! -f "$dst_prefs" ] && touch "$dst_prefs"
        for pn in "extensions.webextensions.uuids" "extensions.webextensions.ExtensionStorageIDB.enabled" "extensions.enabledScopes" "xpinstall.signatures.required"; do
            local pl=$(grep "\"${pn}\"" "$src_prefs" 2>/dev/null | head -1)
            if [ -n "$pl" ]; then
                local tmp="${dst_prefs}.tmp.$$"
                grep -v "\"${pn}\"" "$dst_prefs" > "$tmp" 2>/dev/null
                mv -f "$tmp" "$dst_prefs" 2>/dev/null
                echo "$pl" >> "$dst_prefs"
                log_ok "Pref: $pn"
            fi
        done
    fi

    # Extension IndexedDB
    if [ -d "${src_profile}/storage/default" ]; then
        local ext_count=0
        for sd in "${src_profile}/storage/default/moz-extension+++"*; do
            if [ -d "$sd" ]; then
                local dn=$(basename "$sd")
                safe_cp "$sd" "${dst_profile}/storage/default/${dn}" "ext-idb: $dn"
                ext_count=$((ext_count + 1))
            fi
        done
        log_ok "Extension stores: $ext_count"
    fi

    # ==== PHASE 5: Firefox Account / Sync ====
    log_info "PHASE 5: FIREFOX ACCOUNT AND SYNC"

    local src_sp="${src_dd}/shared_prefs"
    local dst_sp="${dst_dd}/shared_prefs"
    if [ -d "$src_sp" ]; then
        mkdir -p "$dst_sp" 2>/dev/null
        for f in "$src_sp"/*; do
            [ ! -f "$f" ] && continue
            local fname=$(basename "$f")
            safe_cp "$f" "${dst_sp}/${fname}" "shared_pref: $fname"
        done
    fi

    safe_cp "${src_files}/firefox.settings.services.mozilla.com" "${dst_files}/firefox.settings.services.mozilla.com" "sync settings"
    safe_cp "${src_files}/datastore" "${dst_files}/datastore" "datastore/"
    safe_cp "${src_files}/mozac.feature.recentlyclosed" "${dst_files}/mozac.feature.recentlyclosed" "recently closed"

    # ==== PHASE 6: Package Name Patching ====
    log_info "PHASE 6: PACKAGE NAME PATCHING"
    if [ -d "$dst_sp" ] && [ "$SRC" != "$DST" ]; then
        for f in "$dst_sp"/*.xml; do
            [ ! -f "$f" ] && continue
            if grep -q "$SRC" "$f" 2>/dev/null; then
                sed -i "s|${SRC}|${DST}|g" "$f" 2>/dev/null
            fi
        done
    fi

    # ==== PHASE 7: Cleanup ====
    log_info "PHASE 7: CLEANUP"
    rm -f  "${dst_profile}/addonStartup.json.lz4" 2>/dev/null
    rm -rf "${dst_profile}/startupCache" 2>/dev/null
    rm -f  "${dst_profile}/compatibility.ini" 2>/dev/null
    rm -f  "${dst_profile}/sessionstore.jsonlz4" 2>/dev/null
    rm -rf "${dst_profile}/sessionstore-backups" 2>/dev/null

    rm -rf "${dst_dd}/cache" "${dst_dd}/cache2" "${dst_dd}/code_cache" 2>/dev/null

    local uid=$(get_uid "$DST")
    if [ -n "$uid" ]; then
        fix_perms "${dst_dd}/files" "$uid"
        fix_perms "${dst_dd}/shared_prefs" "$uid"
    fi

    stop_pkg "$DST"
    log_ok "GECKO MIGRATION COMPLETED! (v5)"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Usage: $0 <source> <target>" >&2
    exit 1
fi

main
