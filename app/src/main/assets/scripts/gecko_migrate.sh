#!/system/bin/sh
# ============================================================
#  gecko_migrate.sh v7
#  Fenix fix: DBs and tabs under files/
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -z "$SCRIPT_DIR" ]; then
    SCRIPT_DIR="/data/local/tmp/browser_migrator/scripts"
fi
. "${SCRIPT_DIR}/common.sh"

SRC="$1"
DST="$2"
# Optional feature flags supplied by the app:
#   $3 = copy Firefox Account state (Gecko release/beta plaintext store)
#   $4 = migrate extensions
#   $5 = migrate open tabs
MIGRATE_ACCOUNT="${3:-1}"
MIGRATE_EXTENSIONS="${4:-1}"
MIGRATE_TABS="${5:-1}"

G_PROFILE=""
G_DATA_DIR=""

# Set by main() before the copy phases. Kept global so the helpers below can
# resolve per-file locations: Fenix keeps places/cookies under files/, while
# classic Firefox keeps them in the profile directory.
G_SRC_PROFILE=""
G_SRC_FILES=""
G_DST_PROFILE=""
G_DST_FILES=""
G_DST_DB_DIR=""

gecko_src_path() {
    local name="$1"
    if [ -n "$G_SRC_FILES" ] && [ -f "${G_SRC_FILES}/${name}" ]; then
        echo "${G_SRC_FILES}/${name}"
        return 0
    fi
    if [ -n "$G_SRC_PROFILE" ] && [ -f "${G_SRC_PROFILE}/${name}" ]; then
        echo "${G_SRC_PROFILE}/${name}"
        return 0
    fi
    return 1
}

gecko_dst_path() {
    local name="$1"
    if [ -n "$G_DST_DB_DIR" ] && [ -f "${G_DST_DB_DIR}/${name}" ]; then
        echo "${G_DST_DB_DIR}/${name}"
        return 0
    fi
    if [ -n "$G_DST_FILES" ] && [ -f "${G_DST_FILES}/${name}" ]; then
        echo "${G_DST_FILES}/${name}"
        return 0
    fi
    if [ -n "$G_DST_PROFILE" ] && [ -f "${G_DST_PROFILE}/${name}" ]; then
        echo "${G_DST_PROFILE}/${name}"
        return 0
    fi
    if [ -n "$G_DST_DB_DIR" ]; then
        echo "${G_DST_DB_DIR}/${name}"
        return 0
    fi
    if [ -n "$G_DST_FILES" ]; then
        echo "${G_DST_FILES}/${name}"
        return 0
    fi
    echo "${G_DST_PROFILE}/${name}"
}

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
        sleep 9
        stop_pkg "$pkg"
        sleep 2

        find_gecko_profile "$pkg"
        if [ -n "$G_PROFILE" ]; then
            log_ok "Clean profile created: $G_PROFILE"
            return 0
        fi
        attempt=$((attempt + 1))
    done

    # Android may block background launches. Create the conventional profile
    # directory; Firefox initialises it on the next launch.
    local dd
    dd=$(find_data_dir "$pkg")
    if [ -n "$dd" ]; then
        local md="${dd}/files/mozilla"
        if [ ! -f "${md}/profiles.ini" ]; then
            local name="migrator.$(date +%s).default"
            mkdir -p "${md}/${name}" 2>/dev/null
            cat > "${md}/profiles.ini" << INIEOF
[Profile0]
Name=default
IsRelative=1
Path=$name
Default=1
[General]
StartWithLastProfile=1
Version=2
INIEOF
            G_PROFILE="${md}/${name}"
            log_warn "Browser did not create a profile; using empty profile: $G_PROFILE"
            return 0
        fi
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
    log_info "  Gecko Migration v7 (Fenix compatible)"
    log_info "  Source: $SRC"
    log_info "  Target: $DST"
    log_info "  Options: account=$MIGRATE_ACCOUNT extensions=$MIGRATE_EXTENSIONS tabs=$MIGRATE_TABS"
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

    G_SRC_PROFILE="$src_profile"
    G_SRC_FILES="$src_files"
    G_DST_PROFILE="$dst_profile"
    G_DST_FILES="$dst_files"

    # Fenix stores its databases in files/; classic Gecko stores them in the
    # profile. Follow whichever layout the target already uses.
    if [ -f "${dst_files}/places.sqlite" ]; then
        G_DST_DB_DIR="$dst_files"
    elif [ -f "${dst_profile}/places.sqlite" ]; then
        G_DST_DB_DIR="$dst_profile"
    else
        G_DST_DB_DIR="$dst_files"
    fi
    log_info "Target database directory: $G_DST_DB_DIR"

    # Backup
    log_info "Backing up target..."
    mkdir -p "${BACKUP_DIR}/target_files" 2>/dev/null
    cp -rf "$dst_files/." "${BACKUP_DIR}/target_files/" 2>/dev/null
    mkdir -p "${BACKUP_DIR}/target_shared_prefs" 2>/dev/null
    cp -rf "${dst_dd}/shared_prefs/." "${BACKUP_DIR}/target_shared_prefs/" 2>/dev/null || true
    save_manifest "$DST" "GECKO" "$dst_profile" "$dst_dd" "" "files"
    log_ok "Backup: $BACKUP_DIR"

    stop_pkg "$DST"
    sleep 1

    # ==== PHASE 2: Fenix files/ Level DBs ====
    log_info "PHASE 2: FILES-LEVEL DATA"

    # --- places.sqlite (History + Bookmarks) ---
    local src_places dst_places
    src_places=$(gecko_src_path "places.sqlite" 2>/dev/null || true)
    if [ -n "$src_places" ]; then
        dst_places=$(gecko_dst_path "places.sqlite")
        log_info "places.sqlite: $src_places -> $dst_places"
        copy_db_with_sidecars "$src_places" "$dst_places" "places.sqlite"
    fi

    # --- logins.json + key4.db (Passwords) ---
    local src_file dst_file
    for name in logins.json key4.db; do
        src_file=$(gecko_src_path "$name" 2>/dev/null || true)
        if [ -n "$src_file" ]; then
            dst_file=$(gecko_dst_path "$name")
            if [ "$name" = "key4.db" ]; then
                copy_db_with_sidecars "$src_file" "$dst_file" "$name"
            else
                safe_cp "$src_file" "$dst_file" "$name"
            fi
        fi
    done

    # --- Open tabs (optional) ---
    if [ "$MIGRATE_TABS" = "1" ]; then
    # --- tabs.sqlite (Tabs) ---
    src_file=$(gecko_src_path "tabs.sqlite" 2>/dev/null || true)
    if [ -n "$src_file" ]; then
        dst_file=$(gecko_dst_path "tabs.sqlite")
        copy_db_with_sidecars "$src_file" "$dst_file" "tabs.sqlite"
    fi

    # --- Open-tab session storage (Fenix/Firefox Android) ---
    # tabs.sqlite is not the live tab session. Fenix stores the open-tab
    # session in mozilla_components_session_storage_gecko.json, and some
    # builds also have sessionstore.jsonlz4. Without these files the target
    # starts with an empty tab tray even though tabs.sqlite was copied.
    #
    # The old v5 script skipped this file because an old Iceraven/Gecko build
    # crashed on a mismatched JSON schema. Keep the guard for those legacy
    # targets, but copy it for current Fenix/Firefox Beta profiles.
    local session_skip=0
    case "$SRC $DST" in
        *iceraven*|*Iceraven*|*fennec*|*Fennec*|*tor*|*Tor*) session_skip=1 ;;
    esac

    # Remove the target's stale session before installing the source session.
    rm -f "${dst_profile}/sessionstore.jsonlz4" 2>/dev/null
    rm -rf "${dst_profile}/sessionstore-backups" 2>/dev/null

    if [ "$session_skip" -eq 1 ]; then
        log_warn "Open-tab session JSON skipped for legacy Gecko target"
    else
        local src_session="${src_files}/mozilla_components_session_storage_gecko.json"
        if [ -f "$src_session" ]; then
            local dst_session="${dst_files}/mozilla_components_session_storage_gecko.json"
            safe_cp "$src_session" "$dst_session" "open-tab session storage"
            if [ -f "$dst_session" ]; then
                sed -i "s|${src_dd}|${dst_dd}|g; s|${src_profile}|${dst_profile}|g; s|${SRC}|${DST}|g"                     "$dst_session" 2>/dev/null || true
                log_ok "Open-tab session paths patched"
            fi
        else
            log_info "Open-tab session storage not present in source"
        fi

        # Fallback for builds that still use a jsonlz4 sessionstore.
        if [ -f "${src_profile}/sessionstore.jsonlz4" ]; then
            safe_cp "${src_profile}/sessionstore.jsonlz4"                     "${dst_profile}/sessionstore.jsonlz4" "sessionstore.jsonlz4"
        fi
    fi
    else
        log_info "Open-tab migration disabled; target tabs preserved"
    fi

    # --- push.sqlite ---
    src_file=$(gecko_src_path "push.sqlite" 2>/dev/null || true)
    if [ -n "$src_file" ]; then
        dst_file=$(gecko_dst_path "push.sqlite")
        copy_db_with_sidecars "$src_file" "$dst_file" "push.sqlite"
    fi

    # --- Other files-level files ---
    for f in "nimbus_messages_metadata.json" "profileInstalled" "mozilla_components_service_mars_tiles.json"; do
        safe_cp "${src_files}/${f}" "${dst_files}/${f}" "$f"
    done

    # ==== PHASE 3: Profile-Level Data ====
    log_info "PHASE 3: PROFILE-LEVEL DATA"

    for f in \
        favicons.sqlite \
        formhistory.sqlite \
        cookies.sqlite \
        cert9.db permissions.sqlite content-prefs.sqlite \
        webappsstore.sqlite \
    ; do
        src_file=$(gecko_src_path "$f" 2>/dev/null || true)
        if [ -n "$src_file" ]; then
            dst_file=$(gecko_dst_path "$f")
            copy_db_with_sidecars "$src_file" "$dst_file" "$f"
        fi
    done

    # ==== PHASE 4: Extensions ====
    log_info "PHASE 4: EXTENSION MIGRATION"

    if [ "$MIGRATE_EXTENSIONS" = "1" ]; then
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
    else
        log_info "Extension migration disabled; target extensions preserved"
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

            # Account state is handled explicitly below. Crypto files are
            # Keystore-bound and must never be transplanted.
            case "$fname" in
                Fxa*|fxa*|SyncAuthInfoCache.xml|syncPrefs.xml|syncEngines.xml|sync.logins.prefs.xml|mozac_feature_accounts_push.xml|*Crypto.xml)
                    continue
                    ;;
                "${DST}_preferences.xml")
                    # This is the target's own settings file. Preserve it.
                    continue
                    ;;
            esac

            safe_cp "$f" "${dst_sp}/${fname}" "shared_pref: $fname"
        done
    fi

    if [ "$MIGRATE_ACCOUNT" != "1" ]; then
        log_info "Firefox Account copy disabled; target account state preserved"
    elif [ -f "$src_sp/fxaStateAC.xml" ] || [ -f "$src_sp/fxaStatePrefAC.xml" ]; then
        # Firefox Nightly/debug uses SecureAbove22Preferences and a key that is
        # bound to the source app UID. Copying it produces an undecryptable
        # account and can leave the target stuck on the account screen.
        case "$SRC" in
            *nightly*|*debug*)
                log_warn "Firefox Account uses Keystore-protected Nightly/debug storage; account state was not copied. Sign in once in the target."
                ;;
            *)
                # Release/beta can have an old secure file left behind. Their
                # live account state is the plain-text fxaAppState.xml.
                if [ -f "$src_sp/fxaAppState.xml" ]; then
                    safe_cp "$src_sp/fxaAppState.xml" "$dst_sp/fxaAppState.xml" "Firefox Account state (release/beta)"
                    for n in SyncAuthInfoCache.xml syncPrefs.xml syncEngines.xml sync.logins.prefs.xml mozac_feature_accounts_push.xml; do
                        [ -f "$src_sp/$n" ] && safe_cp "$src_sp/$n" "$dst_sp/$n" "sync pref: $n"
                    done
                    log_ok "Firefox Account state copied; the target should stay signed in"
                    log_info "Target account state was backed up in $BACKUP_DIR/target_shared_prefs"
                else
                    log_warn "Firefox Account uses Keystore-protected storage; account state was not copied. Sign in once in the target."
                fi
                ;;
        esac
    elif [ -f "$src_sp/fxaAppState.xml" ]; then
        # Firefox release/beta use the plain-text shared preference store:
        # fxaAppState.xml contains the serialized FxA state under "fxaState".
        # This is the path that has always worked for release/beta migrations.
        safe_cp "$src_sp/fxaAppState.xml" "$dst_sp/fxaAppState.xml" "Firefox Account state (release/beta)"
        for n in SyncAuthInfoCache.xml syncPrefs.xml syncEngines.xml sync.logins.prefs.xml mozac_feature_accounts_push.xml; do
            [ -f "$src_sp/$n" ] && safe_cp "$src_sp/$n" "$dst_sp/$n" "sync pref: $n"
        done
        log_ok "Firefox Account state copied; the target should stay signed in"
        log_info "Target account state was backed up in $BACKUP_DIR/target_shared_prefs"
    else
        log_info "No Firefox Account state found in source; target account preserved"
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

    rm -rf "${dst_dd}/cache" "${dst_dd}/cache2" "${dst_dd}/code_cache" 2>/dev/null

    local uid=$(get_uid "$DST")
    if [ -n "$uid" ]; then
        fix_perms "${dst_dd}/files" "$uid"
        [ -d "${dst_dd}/shared_prefs" ] && fix_perms "${dst_dd}/shared_prefs" "$uid"
    fi

    stop_pkg "$DST"
    log_ok "GECKO MIGRATION COMPLETED! (v6)"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Usage: $0 <source> <target>" >&2
    exit 1
fi

main
