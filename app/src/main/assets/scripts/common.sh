#!/system/bin/sh
# ============================================================
#  common.sh v3 — stdout/stderr separation fixed
# ============================================================

WORK_DIR="/data/local/tmp/browser_migrator"
LOG_FILE="${WORK_DIR}/migration.log"
BACKUP_DIR=""
SQLITE3_BIN=""

# ============================================================
#  LOG — ALWAYS write to stderr (don't pollute stdout)
#  Since it's merged with 2>&1 on the Kotlin side,
#  the user sees everything.
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
#  FIND PACKAGE DATA DIRECTORY
#  /data/data symlink might not work — try multiple paths
# ============================================================
find_data_dir() {
    local pkg="$1"

    # Method 1: /data/data (most common)
    if [ -d "/data/data/$pkg" ]; then
        echo "/data/data/$pkg"
        return 0
    fi

    # Method 2: /data/user/0 (real path)
    if [ -d "/data/user/0/$pkg" ]; then
        echo "/data/user/0/$pkg"
        return 0
    fi

    # Method 3: Ask via dumpsys
    local dd
    dd=$(dumpsys package "$pkg" 2>/dev/null | grep "dataDir=" | head -1 | sed 's/.*dataDir=//' | tr -d ' \r')
    if [ -n "$dd" ] && [ -d "$dd" ]; then
        echo "$dd"
        return 0
    fi

    # Method 4: Ask via pm
    dd=$(pm dump "$pkg" 2>/dev/null | grep "dataDir=" | head -1 | sed 's/.*dataDir=//' | tr -d ' \r')
    if [ -n "$dd" ] && [ -d "$dd" ]; then
        echo "$dd"
        return 0
    fi

    # Method 5: Search under /data/user
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
        log_error "Root access required!"
        exit 1
    fi
    log_ok "Root verified"
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

    log_info "sqlite3 not found — advanced SQLite path rewriting skipped (data is still copied)"
    SQLITE3_BIN=""
    return 1
}

# ============================================================
#  PACKAGE
# ============================================================
check_package() {
    if pm list packages 2>/dev/null | grep -q "package:${1}$"; then
        log_ok "Package exists: $1"
        return 0
    fi
    log_error "Package missing: $1"
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
    log_info "Stopping: $1"
    am force-stop "$1" 2>/dev/null
    sleep 1
    log_ok "Stopped: $1"
}

# Browsers flush their live session and preference stores from onStop().
# am force-stop kills the process without delivering that callback, which is
# why a freshly used tab can be missing even though the session file exists.
# Send the app to the background first, give it time to write, then stop it.
graceful_stop_pkg() {
    log_info "Pausing for flush: $1"
    input keyevent 3 >/dev/null 2>&1 || true
    am start -a android.intent.action.MAIN -c android.intent.category.HOME \
        >/dev/null 2>&1 || true
    sleep 3
    am force-stop "$1" 2>/dev/null
    sleep 1
    log_ok "Stopped after flush: $1"
}

# ============================================================
#  DISK SPACE
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
    log_info "Disk: ${avail_mb}MB available, ${need_mb}MB required"

    if [ "$avail_mb" -lt "$need_mb" ] 2>/dev/null; then
        log_error "INSUFFICIENT DISK SPACE!"
        exit 1
    fi
    return 0
}

# ============================================================
#  COPYING
# ============================================================
safe_cp() {
    local src="$1" dst="$2" desc="$3"

    if [ ! -e "$src" ]; then
        # Optional files are normal to miss (Secure Preferences, Login Data,
        # extension folders, ...). Do not turn every one into a scary warning.
        log_info "Not present, skipped: $desc"
        return 1
    fi

    # backup
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
        log_ok "Copied: $desc"
    else
        log_error "Copy failed: $desc"
        return 1
    fi
}

# Remove target-side WAL/journal files while the browser is stopped. If a
# source DB has no matching sidecar, leaving an old target sidecar behind can
# make SQLite replay stale pages against the newly copied database.
remove_sidecars() {
    local path="$1"
    rm -f "${path}-wal" "${path}-shm" "${path}-journal" 2>/dev/null || true
}

# Copy a SQLite database and whichever sidecars actually exist in the source.
copy_db_with_sidecars() {
    local src="$1" dst="$2" desc="$3" sfx
    if [ ! -e "$src" ]; then
        log_info "Not present, skipped: $desc"
        return 1
    fi

    remove_sidecars "$dst"
    safe_cp "$src" "$dst" "$desc"
    for sfx in "-wal" "-shm" "-journal"; do
        if [ -e "${src}${sfx}" ]; then
            safe_cp "${src}${sfx}" "${dst}${sfx}" "${desc}${sfx}"
        fi
    done
}

# ============================================================
#  PERMISSIONS
# ============================================================
fix_perms() {
    local path="$1" uid="$2"
    if [ -z "$uid" ] || [ ! -e "$path" ]; then
        log_error "Failed to fix permissions: $path"
        return 1
    fi
    chown -R "${uid}:${uid}" "$path" 2>/dev/null
    find "$path" -type d -exec chmod 700 {} \; 2>/dev/null
    find "$path" -type f -exec chmod 600 {} \; 2>/dev/null
    if command -v restorecon >/dev/null 2>&1; then
        restorecon -RF "$path" 2>/dev/null
    fi
    log_ok "Permissions fixed: $path"
}

# ============================================================
#  BACKUP MANIFEST
# ============================================================
save_manifest() {
    if [ -n "$BACKUP_DIR" ]; then
        local target="$1"
        local engine="$2"
        local profile="$3"
        local data_dir="${4:-}"
        local base_dir="${5:-}"
        local kind="${6:-profile}"
        cat > "${BACKUP_DIR}/manifest.txt" << MFEOF
TARGET=$target
TARGET_PACKAGE=$target
ENGINE=$engine
PROFILE=$profile
TARGET_PROFILE=$profile
DATA_DIR=$data_dir
TARGET_DATA_DIR=$data_dir
BASE_DIR=$base_dir
TARGET_BASE=$base_dir
BACKUP_KIND=$kind
DATE=$(date)
MFEOF
    fi
}

# ============================================================
#  ROLLBACK
# ============================================================
perform_rollback() {
    local backup_dir="$1"
    local manifest="${backup_dir}/manifest.txt"
    [ -f "$manifest" ] || manifest="${backup_dir}/rollback_manifest.txt"
    if [ ! -f "$manifest" ]; then
        log_error "Rollback manifest not found in $backup_dir"
        return 1
    fi

    local target_pkg engine target_profile target_data_dir backup_kind target_base
    target_pkg=$(grep -m1 '^TARGET_PACKAGE=' "$manifest" | cut -d'=' -f2-)
    [ -z "$target_pkg" ] && target_pkg=$(grep -m1 '^TARGET=' "$manifest" | cut -d'=' -f2-)
    engine=$(grep -m1 '^ENGINE=' "$manifest" | cut -d'=' -f2-)
    target_profile=$(grep -m1 '^TARGET_PROFILE=' "$manifest" | cut -d'=' -f2-)
    [ -z "$target_profile" ] && target_profile=$(grep -m1 '^PROFILE=' "$manifest" | cut -d'=' -f2-)
    target_data_dir=$(grep -m1 '^TARGET_DATA_DIR=' "$manifest" | cut -d'=' -f2-)
    [ -z "$target_data_dir" ] && target_data_dir=$(grep -m1 '^DATA_DIR=' "$manifest" | cut -d'=' -f2-)
    target_base=$(grep -m1 '^TARGET_BASE=' "$manifest" | cut -d'=' -f2-)
    [ -z "$target_base" ] && target_base=$(grep -m1 '^BASE_DIR=' "$manifest" | cut -d'=' -f2-)
    backup_kind=$(grep -m1 '^BACKUP_KIND=' "$manifest" | cut -d'=' -f2-)

    if [ -z "$target_pkg" ]; then
        log_error "Rollback manifest has no target package"
        return 1
    fi

    local restore_from restore_to
    if [ "$engine" = "GECKO" ] || [ "$backup_kind" = "files" ]; then
        restore_from="${backup_dir}/target_files"
        [ -d "$restore_from" ] || restore_from="${backup_dir}/target_original_files"
        if [ -z "$target_data_dir" ] && [ -n "$target_profile" ]; then
            # .../files/mozilla/<profile> -> /data/data/<pkg>
            target_data_dir=$(dirname "$(dirname "$(dirname "$target_profile")")")
        fi
        restore_to="${target_data_dir}/files"
    else
        restore_from="${backup_dir}/target_original_profile"
        [ -d "$restore_from" ] || restore_from="${backup_dir}/target_profile"
        restore_to="$target_profile"
    fi

    if [ ! -d "$restore_from" ] || [ -z "$restore_to" ]; then
        log_error "Backup payload missing for $target_pkg"
        return 1
    fi

    log_info "Restoring $target_pkg from $restore_from"
    stop_pkg "$target_pkg"
    rm -rf "$restore_to" 2>/dev/null
    mkdir -p "$restore_to" 2>/dev/null
    if ! cp -rf "${restore_from}/." "${restore_to}/" 2>/dev/null; then
        log_error "Could not copy backup back to $restore_to"
        return 1
    fi

    if [ "$engine" = "GECKO" ] || [ "$backup_kind" = "files" ]; then
        if [ -d "${backup_dir}/target_shared_prefs" ] && [ -n "$target_data_dir" ]; then
            rm -rf "${target_data_dir}/shared_prefs" 2>/dev/null
            mkdir -p "${target_data_dir}/shared_prefs" 2>/dev/null
            cp -rf "${backup_dir}/target_shared_prefs/." \
                "${target_data_dir}/shared_prefs/" 2>/dev/null || true
        fi
    else
        if [ -z "$target_base" ] && [ -n "$target_profile" ]; then
            target_base=$(dirname "$target_profile")
        fi
        if [ -n "$target_base" ] && [ -f "${backup_dir}/target_local_state/Local State" ]; then
            cp -f "${backup_dir}/target_local_state/Local State" \
                "${target_base}/Local State" 2>/dev/null || true
        fi
    fi

    local uid
    uid=$(get_uid "$target_pkg")
    if [ -n "$uid" ]; then
        fix_perms "$restore_to" "$uid"
        if [ "$engine" = "GECKO" ] || [ "$backup_kind" = "files" ]; then
            [ -d "${target_data_dir}/shared_prefs" ] && \
                fix_perms "${target_data_dir}/shared_prefs" "$uid"
        elif [ -n "$target_base" ] && [ -f "${target_base}/Local State" ]; then
            chown "${uid}:${uid}" "${target_base}/Local State" 2>/dev/null
            chmod 600 "${target_base}/Local State" 2>/dev/null
        fi
    else
        log_warn "Could not determine UID for $target_pkg; permissions not changed"
    fi
    log_ok "Rollback completed for $target_pkg"
    return 0
}

log_info "common.sh v3 loaded"
