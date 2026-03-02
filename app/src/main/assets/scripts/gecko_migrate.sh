#!/system/bin/sh
# ============================================================
#  gecko_migrate.sh — GeckoView goc scripti (v2)
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -z "$SCRIPT_DIR" ]; then
    SCRIPT_DIR="/data/local/tmp/browser_migrator/scripts"
fi
. "${SCRIPT_DIR}/common.sh"

SRC="$1"
DST="$2"

# ============================================================
#  PROFİL KEŞFİ (4 yedek yontem)
# ============================================================
find_gecko_profile() {
    local pkg="$1"
    local base="/data/data/$pkg/files/mozilla"

    log_info "Profil araniyor: $base"

    if [ ! -d "$base" ]; then
        log_error "Mozilla dizini yok: $base"
        # Belki farkli konum?
        for alt in "/data/data/$pkg/files" "/data/data/$pkg"; do
            if [ -d "$alt/mozilla" ]; then
                base="$alt/mozilla"
                log_info "Alternatif bulundu: $base"
                break
            fi
        done
        if [ ! -d "$base" ]; then
            echo ""
            return 1
        fi
    fi

    # Debug: dizin icerigini goster
    log_info "--- Dizin icerigi: $base ---"
    ls -la "$base/" 2>/dev/null | while IFS= read -r line; do
        log_info "  $line"
    done
    log_info "--- Bitti ---"

    # Yontem 1: profiles.ini
    if [ -f "$base/profiles.ini" ]; then
        log_info "profiles.ini bulundu, okunuyor..."
        local ini_path
        ini_path=$(grep "^Path=" "$base/profiles.ini" 2>/dev/null | head -1 | cut -d= -f2)
        # CR/LF temizle
        ini_path=$(echo "$ini_path" | tr -d '\r\n' | tr -d ' ')
        if [ -n "$ini_path" ] && [ -d "$base/$ini_path" ]; then
            log_ok "Profil (profiles.ini): $base/$ini_path"
            echo "$base/$ini_path"
            return 0
        else
            log_warn "profiles.ini path gecersiz: '$ini_path'"
        fi
    fi

    # Yontem 2: *.default* glob
    for d in "$base"/*; do
        if [ -d "$d" ]; then
            local dname
            dname=$(basename "$d")
            case "$dname" in
                *.default*|*.release*|*.nightly*)
                    log_ok "Profil (glob): $d"
                    echo "$d"
                    return 0
                    ;;
            esac
        fi
    done

    # Yontem 3: places.sqlite iceren dizin
    for d in "$base"/*/; do
        if [ -d "$d" ] && [ -f "${d}places.sqlite" ]; then
            local p="${d%/}"
            log_ok "Profil (places.sqlite): $p"
            echo "$p"
            return 0
        fi
    done

    # Yontem 4: Herhangi bir alt dizin
    for d in "$base"/*/; do
        if [ -d "$d" ]; then
            local p="${d%/}"
            log_warn "Profil (son care — ilk dizin): $p"
            echo "$p"
            return 0
        fi
    done

    log_error "Hicbir profil bulunamadi: $base"
    echo ""
    return 1
}

# ============================================================
#  HEDEF PROFİL GARANTİSİ
# ============================================================
ensure_dst_profile() {
    local pkg="$1"
    local p
    p=$(find_gecko_profile "$pkg")

    if [ -n "$p" ] && [ -d "$p" ]; then
        echo "$p"
        return 0
    fi

    log_info "Hedef profil yok, tarayici bir kez baslatiliyor..."
    local intent
    intent=$(cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
    if [ -n "$intent" ]; then
        log_info "Baslatiliyor: am start -n $intent"
        am start -n "$intent" >/dev/null 2>&1
    else
        log_info "monkey ile baslatiliyor..."
        monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    fi

    log_info "8 saniye bekleniyor..."
    sleep 8
    stop_pkg "$pkg"
    sleep 2

    p=$(find_gecko_profile "$pkg")
    if [ -n "$p" ] && [ -d "$p" ]; then
        echo "$p"
        return 0
    fi

    log_error "Profil olusturulamadi: $pkg"
    echo ""
    return 1
}

# ============================================================
#  ANA AKIS
# ============================================================
main() {
    log_init

    log_info "============================================"
    log_info "  Gecko Goc — v2"
    log_info "  Kaynak: $SRC"
    log_info "  Hedef:  $DST"
    log_info "============================================"

    check_root
    check_sqlite3
    check_package "$SRC" || exit 1
    check_package "$DST" || exit 1

    stop_pkg "$SRC"
    stop_pkg "$DST"

    # ---- FAZA 0: Kesif ----
    log_info "FAZA 0: KESIF"

    local src_profile
    src_profile=$(find_gecko_profile "$SRC")
    if [ -z "$src_profile" ]; then
        log_error "KAYNAK PROFIL BULUNAMADI!"
        log_error "Kontrol edin: /data/data/$SRC/files/mozilla/"
        log_info "Icerik:"
        ls -laR "/data/data/$SRC/files/" 2>/dev/null | head -30
        exit 1
    fi
    log_ok "Kaynak: $src_profile"
    local src_name
    src_name=$(basename "$src_profile")

    check_disk "$src_profile"

    # ---- FAZA 1: Hedef Profil ----
    log_info "FAZA 1: HEDEF PROFIL"

    local dst_profile
    dst_profile=$(ensure_dst_profile "$DST")
    if [ -z "$dst_profile" ]; then
        log_error "HEDEF PROFIL HAZIRLANAMAADI!"
        exit 1
    fi
    log_ok "Hedef: $dst_profile"
    local dst_name
    dst_name=$(basename "$dst_profile")

    # Yedekle
    log_info "Hedef yedekleniyor..."
    mkdir -p "${BACKUP_DIR}/target_original_profile" 2>/dev/null
    cp -rf "$dst_profile/." "${BACKUP_DIR}/target_original_profile/" 2>/dev/null
    save_manifest "$DST" "GECKO" "$dst_profile"
    log_ok "Yedek alindi: $BACKUP_DIR"

    stop_pkg "$DST"
    sleep 1

    # ---- FAZA 2: Cekirdek Veri ----
    log_info "FAZA 2: CEKIRDEK VERI KOPYALAMA"

    for f in \
        places.sqlite places.sqlite-wal places.sqlite-shm \
        favicons.sqlite favicons.sqlite-wal favicons.sqlite-shm \
        logins.json key4.db \
        formhistory.sqlite cookies.sqlite cookies.sqlite-wal \
        cert9.db permissions.sqlite content-prefs.sqlite \
    ; do
        safe_cp "${src_profile}/${f}" "${dst_profile}/${f}" "$f"
    done

    # ---- FAZA 3: Eklentiler ----
    log_info "FAZA 3: EKLENTI GOCU"

    # XPI dosyalari
    safe_cp "${src_profile}/extensions" "${dst_profile}/extensions" "extensions/"

    # Metadata
    safe_cp "${src_profile}/extensions.json" \
            "${dst_profile}/extensions.json" "extensions.json"
    safe_cp "${src_profile}/extension-preferences.json" \
            "${dst_profile}/extension-preferences.json" "extension-preferences.json"

    # Sync DB
    safe_cp "${src_profile}/storage-sync-v2.sqlite" \
            "${dst_profile}/storage-sync-v2.sqlite" "storage-sync-v2.sqlite"

    # localStorage
    safe_cp "${src_profile}/browser-extension-data" \
            "${dst_profile}/browser-extension-data" "browser-extension-data/"

    # --- extensions.json yol yamalama ---
    if [ -f "${dst_profile}/extensions.json" ]; then
        log_info "extensions.json yollari yamalaniyor..."
        local sp="/data/data/${SRC}/files/mozilla/${src_name}"
        local dp="/data/data/${DST}/files/mozilla/${dst_name}"
        sed -i "s|${sp}|${dp}|g" "${dst_profile}/extensions.json" 2>/dev/null
        sed -i "s|/data/data/${SRC}/|/data/data/${DST}/|g" "${dst_profile}/extensions.json" 2>/dev/null
        log_ok "extensions.json yamalandi"
    fi

    # --- UUID senkronizasyonu ---
    log_info "UUID senkronizasyonu..."
    local src_prefs="${src_profile}/prefs.js"
    local dst_prefs="${dst_profile}/prefs.js"

    if [ -f "$src_prefs" ]; then
        [ ! -f "$dst_prefs" ] && touch "$dst_prefs"

        for pn in \
            "extensions.webextensions.uuids" \
            "extensions.webextensions.ExtensionStorageIDB.enabled" \
            "extensions.enabledScopes" \
            "xpinstall.signatures.required" \
        ; do
            local pl
            pl=$(grep "\"${pn}\"" "$src_prefs" 2>/dev/null | head -1)
            if [ -n "$pl" ]; then
                # Eskiyi kaldir
                local tmp="${dst_prefs}.tmp.$$"
                grep -v "\"${pn}\"" "$dst_prefs" > "$tmp" 2>/dev/null
                mv -f "$tmp" "$dst_prefs" 2>/dev/null
                # Yenisini ekle
                echo "$pl" >> "$dst_prefs"
                log_ok "Pref: $pn"
            fi
        done
    else
        log_warn "Kaynak prefs.js yok, UUID atlaniyor"
    fi

    # --- Extension IndexedDB depolari ---
    if [ -d "${src_profile}/storage/default" ]; then
        local ext_count=0
        for sd in "${src_profile}/storage/default/moz-extension+++"*; do
            if [ -d "$sd" ]; then
                local dn
                dn=$(basename "$sd")
                safe_cp "$sd" "${dst_profile}/storage/default/${dn}" "ext-idb: $dn"
                ext_count=$((ext_count + 1))
            fi
        done
        log_ok "Eklenti depolari: $ext_count adet"
    fi

    # ---- FAZA 4: Temizlik ----
    log_info "FAZA 4: TEMIZLIK"

    # Tehlikeli onbellekleri sil
    rm -f  "${dst_profile}/addonStartup.json.lz4" 2>/dev/null
    rm -rf "${dst_profile}/startupCache" 2>/dev/null
    rm -f  "${dst_profile}/compatibility.ini" 2>/dev/null

    # Session (cokme onlemi)
    for sf in sessionstore.jsonlz4 sessionstore-backups \
              sessionstore.js sessionstore.bak; do
        rm -rf "${dst_profile}/${sf}" 2>/dev/null
    done
    log_ok "Onbellek ve session temizlendi"

    # Genel cache
    rm -rf "/data/data/${DST}/cache" 2>/dev/null
    rm -rf "/data/data/${DST}/cache2" 2>/dev/null

    # Sahiplik + SELinux
    local uid
    uid=$(get_uid "$DST")
    if [ -n "$uid" ]; then
        fix_perms "/data/data/${DST}/files/mozilla" "$uid"
    else
        log_error "UID alinamadi! Izinler BOZUK olabilir"
    fi

    stop_pkg "$DST"

    log_info "============================================"
    log_ok   "GECKO GOCU TAMAMLANDI!"
    log_info "Yedek: $BACKUP_DIR"
    log_info "============================================"
}

# ---- Giris kontrolu ----
if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Kullanim: $0 <kaynak_paket> <hedef_paket>"
    exit 1
fi

main
