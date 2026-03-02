#!/system/bin/sh
# ============================================================
#  gecko_migrate.sh v4
#  Fenix düzeltmesi: files/ altindaki DB'ler + sekmeler
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
#  VERİ DİZİNİ + PROFİL KEŞFİ
# ============================================================
resolve_data_dir() {
    local pkg="$1"
    G_DATA_DIR=""
    G_DATA_DIR=$(find_data_dir "$pkg")
    if [ -z "$G_DATA_DIR" ]; then
        log_error "Veri dizini bulunamadi: $pkg"
        return 1
    fi
    log_ok "Veri dizini: $G_DATA_DIR"
    return 0
}

find_gecko_profile() {
    local pkg="$1"
    G_PROFILE=""

    resolve_data_dir "$pkg"
    if [ -z "$G_DATA_DIR" ]; then return 1; fi

    local dd="$G_DATA_DIR"

    # Debug
    log_info "--- $dd/files/ icerigi ---"
    if [ -d "$dd/files" ]; then
        ls -la "$dd/files/" 2>/dev/null | while IFS= read -r line; do
            log_info "  $line"
        done
    fi

    # 1. files/mozilla/ altinda profil ara
    local mozilla_dir="$dd/files/mozilla"
    if [ -d "$mozilla_dir" ]; then
        log_info "files/mozilla/ bulundu"

        if [ -f "$mozilla_dir/profiles.ini" ]; then
            local ini_path
            ini_path=$(grep "^Path=" "$mozilla_dir/profiles.ini" 2>/dev/null | head -1 | cut -d= -f2 | tr -d '\r\n ')
            if [ -n "$ini_path" ] && [ -d "$mozilla_dir/$ini_path" ]; then
                G_PROFILE="$mozilla_dir/$ini_path"
                log_ok "Profil (profiles.ini): $G_PROFILE"
                return 0
            fi
        fi

        for d in "$mozilla_dir"/*/; do
            [ ! -d "$d" ] && continue
            local dname=$(basename "$d")
            case "$dname" in
                *.default*|*.svc*|*.release*|*.nightly*)
                    G_PROFILE="${d%/}"
                    log_ok "Profil (glob): $G_PROFILE"
                    return 0
                    ;;
            esac
        done

        for d in "$mozilla_dir"/*/; do
            if [ -d "$d" ]; then
                G_PROFILE="${d%/}"
                log_warn "Profil (ilk dizin): $G_PROFILE"
                return 0
            fi
        done
    fi

    # 2. Genis arama
    if [ -d "$dd/files" ]; then
        local found
        found=$(find "$dd/files" -name "places.sqlite" -type f 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            G_PROFILE=$(dirname "$found")
            log_ok "Profil (find): $G_PROFILE"
            return 0
        fi
    fi

    log_error "PROFIL BULUNAMADI: $pkg"
    return 1
}

ensure_dst_profile() {
    local pkg="$1"

    find_gecko_profile "$pkg"
    if [ -n "$G_PROFILE" ]; then
        log_ok "Hedef profil mevcut: $G_PROFILE"
        return 0
    fi

    log_info "Hedef profil yok, tarayici baslatiliyor..."
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
        log_ok "Temiz profil olusturuldu: $G_PROFILE"
        return 0
    fi

    log_error "Profil olusturulamadi: $pkg"
    return 1
}

# ============================================================
#  ANA AKIS
# ============================================================
main() {
    log_init

    log_info "============================================"
    log_info "  Gecko Goc v4 (Fenix uyumlu)"
    log_info "  Kaynak: $SRC"
    log_info "  Hedef:  $DST"
    log_info "============================================"

    check_root
    check_sqlite3
    check_package "$SRC" || exit 1
    check_package "$DST" || exit 1

    stop_pkg "$SRC"
    stop_pkg "$DST"

    # ==== FAZA 0: Kesif ====
    log_info "FAZA 0: KESIF"

    find_gecko_profile "$SRC"
    local src_profile="$G_PROFILE"
    local src_dd="$G_DATA_DIR"
    local src_files="$src_dd/files"

    if [ -z "$src_profile" ]; then
        log_error "KAYNAK PROFIL BULUNAMADI!"
        exit 1
    fi
    log_ok "Kaynak profil: $src_profile"
    log_ok "Kaynak files: $src_files"
    local src_name=$(basename "$src_profile")

    check_disk "$src_files"

    # ==== FAZA 1: HEDEF PROFIL ====
    log_info "FAZA 1: HEDEF PROFIL"

    ensure_dst_profile "$DST"
    local dst_profile="$G_PROFILE"
    local dst_dd="$G_DATA_DIR"
    local dst_files="$dst_dd/files"

    if [ -z "$dst_profile" ]; then
        log_error "HEDEF PROFIL HAZIRLANAMAADI!"
        exit 1
    fi
    log_ok "Hedef profil: $dst_profile"
    log_ok "Hedef files: $dst_files"
    local dst_name=$(basename "$dst_profile")

    # Yedek
    log_info "Hedef yedekleniyor..."
    mkdir -p "${BACKUP_DIR}/target_files" 2>/dev/null
    cp -rf "$dst_files/." "${BACKUP_DIR}/target_files/" 2>/dev/null
    save_manifest "$DST" "GECKO" "$dst_profile"
    log_ok "Yedek: $BACKUP_DIR"

    stop_pkg "$DST"
    sleep 1

    # ==== FAZA 2: Fenix files/ Seviyesi DB'ler ====
    log_info "FAZA 2: FILES-SEVIYESI VERILER"

    # --- places.sqlite (Gecmis + Yer Imleri) ---
    if [ -f "${src_files}/places.sqlite" ]; then
        log_info "places.sqlite files/ altinda (Fenix)"
        safe_cp "${src_files}/places.sqlite"     "${dst_files}/places.sqlite"     "places.sqlite (files/)"
        safe_cp "${src_files}/places.sqlite-wal"  "${dst_files}/places.sqlite-wal"  "places.sqlite-wal (files/)"
        safe_cp "${src_files}/places.sqlite-shm"  "${dst_files}/places.sqlite-shm"  "places.sqlite-shm (files/)"
    elif [ -f "${src_profile}/places.sqlite" ]; then
        log_info "places.sqlite profil altinda (klasik)"
        safe_cp "${src_profile}/places.sqlite"     "${dst_profile}/places.sqlite"     "places.sqlite (profil)"
        safe_cp "${src_profile}/places.sqlite-wal"  "${dst_profile}/places.sqlite-wal"  "places.sqlite-wal (profil)"
        safe_cp "${src_profile}/places.sqlite-shm"  "${dst_profile}/places.sqlite-shm"  "places.sqlite-shm (profil)"
    fi

    # --- logins.json + key4.db (Sifreler) ---
    if [ -f "${src_files}/logins.json" ]; then
        safe_cp "${src_files}/logins.json" "${dst_files}/logins.json" "logins.json (files/)"
    elif [ -f "${src_profile}/logins.json" ]; then
        safe_cp "${src_profile}/logins.json" "${dst_profile}/logins.json" "logins.json (profil)"
    fi

    if [ -f "${src_files}/key4.db" ]; then
        safe_cp "${src_files}/key4.db" "${dst_files}/key4.db" "key4.db (files/)"
    elif [ -f "${src_profile}/key4.db" ]; then
        safe_cp "${src_profile}/key4.db" "${dst_profile}/key4.db" "key4.db (profil)"
    fi

    # --- tabs.sqlite (Sekmeler) ---
    if [ -f "${src_files}/tabs.sqlite" ]; then
        safe_cp "${src_files}/tabs.sqlite"     "${dst_files}/tabs.sqlite"     "tabs.sqlite"
        safe_cp "${src_files}/tabs.sqlite-wal"  "${dst_files}/tabs.sqlite-wal"  "tabs.sqlite-wal"
        safe_cp "${src_files}/tabs.sqlite-shm"  "${dst_files}/tabs.sqlite-shm"  "tabs.sqlite-shm"
    fi

    # --- Session verisi ---
    if [ -f "${src_files}/mozilla_components_session_storage_gecko.json" ]; then
        safe_cp "${src_files}/mozilla_components_session_storage_gecko.json" \
                "${dst_files}/mozilla_components_session_storage_gecko.json" \
                "Session (sekme durumu)"
    fi

    # --- push.sqlite ---
    safe_cp "${src_files}/push.sqlite" "${dst_files}/push.sqlite" "push.sqlite"

    # --- Diger files-seviyesi dosyalar ---
    for f in "nimbus_messages_metadata.json" "profileInstalled" "mozilla_components_service_mars_tiles.json"; do
        safe_cp "${src_files}/${f}" "${dst_files}/${f}" "$f"
    done

    # ==== FAZA 3: Profil-Seviyesi Veriler ====
    log_info "FAZA 3: PROFIL-SEVIYESI VERILER"

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

    # ==== FAZA 4: Eklentiler ====
    log_info "FAZA 4: EKLENTI GOCU"

    safe_cp "${src_profile}/extensions" "${dst_profile}/extensions" "extensions/"
    safe_cp "${src_profile}/extensions.json" "${dst_profile}/extensions.json" "extensions.json"
    safe_cp "${src_profile}/extension-preferences.json" "${dst_profile}/extension-preferences.json" "extension-preferences.json"
    safe_cp "${src_profile}/browser-extension-data" "${dst_profile}/browser-extension-data" "browser-extension-data/"

    if [ -f "${src_profile}/storage-sync-v2.sqlite" ]; then
        safe_cp "${src_profile}/storage-sync-v2.sqlite" "${dst_profile}/storage-sync-v2.sqlite" "storage-sync (profil)"
    fi
    if [ -f "${src_files}/storage-sync-v2.sqlite" ]; then
        safe_cp "${src_files}/storage-sync-v2.sqlite" "${dst_files}/storage-sync-v2.sqlite" "storage-sync (files/)"
    fi

    # extensions.json yol yamalama
    if [ -f "${dst_profile}/extensions.json" ]; then
        log_info "extensions.json yollari yamalaniyor..."
        sed -i "s|${src_dd}/files/mozilla/${src_name}|${dst_dd}/files/mozilla/${dst_name}|g" "${dst_profile}/extensions.json" 2>/dev/null
        sed -i "s|${src_dd}/|${dst_dd}/|g" "${dst_profile}/extensions.json" 2>/dev/null
        sed -i "s|${SRC}|${DST}|g" "${dst_profile}/extensions.json" 2>/dev/null
        log_ok "extensions.json yamalandi"
    fi

    # UUID senkronizasyonu
    log_info "UUID senkronizasyonu..."
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
        log_ok "Eklenti depolari: $ext_count"
    fi

    # ==== FAZA 5: Firefox Hesabi / Sync ====
    log_info "FAZA 5: FIREFOX HESABI VE SYNC"

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

    # ==== FAZA 6: Paket Adi Yamalama ====
    log_info "FAZA 6: PAKET ADI YAMALAMA"
    if [ -d "$dst_sp" ] && [ "$SRC" != "$DST" ]; then
        for f in "$dst_sp"/*.xml; do
            [ ! -f "$f" ] && continue
            if grep -q "$SRC" "$f" 2>/dev/null; then
                sed -i "s|${SRC}|${DST}|g" "$f" 2>/dev/null
            fi
        done
    fi

    # ==== FAZA 7: Temizlik ====
    log_info "FAZA 7: TEMIZLIK"
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
    log_ok "GECKO GOCU TAMAMLANDI! (v4)"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Kullanim: $0 <kaynak> <hedef>" >&2
    exit 1
fi

main
