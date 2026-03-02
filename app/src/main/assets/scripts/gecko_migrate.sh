#!/system/bin/sh
# ============================================================
#  gecko_migrate.sh v3
#  Düzeltmeler:
#    - Global degisken (stdout kirlenmesi onlendi)
#    - /data/user/0 destegi
#    - Genis profil arama
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -z "$SCRIPT_DIR" ]; then
    SCRIPT_DIR="/data/local/tmp/browser_migrator/scripts"
fi
. "${SCRIPT_DIR}/common.sh"

SRC="$1"
DST="$2"

# Global donusler — echo/stdout KULLANILMAZ
G_PROFILE=""
G_DATA_DIR=""

# ============================================================
#  VERI DİZİNİ + PROFİL KEŞFİ
# ============================================================

# Paketin gercek veri dizinini bul
resolve_data_dir() {
    local pkg="$1"
    G_DATA_DIR=""

    G_DATA_DIR=$(find_data_dir "$pkg")

    if [ -z "$G_DATA_DIR" ]; then
        log_error "Veri dizini bulunamadi: $pkg"
        log_info "Kontrol edilen yollar:"
        log_info "  /data/data/$pkg"
        log_info "  /data/user/0/$pkg"

        # Debug: /data altinda paketle ilgili ne var?
        log_info "--- /data altinda arama ---"
        find /data -maxdepth 4 -type d -name "$pkg" 2>/dev/null | while IFS= read -r line; do
            log_info "  Bulundu: $line"
        done
        return 1
    fi

    log_ok "Veri dizini: $G_DATA_DIR"
    return 0
}

# Gecko profil dizinini bul — sonucu G_PROFILE'a yaz
find_gecko_profile() {
    local pkg="$1"
    G_PROFILE=""

    # Veri dizinini coz
    resolve_data_dir "$pkg"
    if [ -z "$G_DATA_DIR" ]; then
        return 1
    fi

    local dd="$G_DATA_DIR"

    # Debug: tum dosya yapisini goster
    log_info "--- $dd/files/ icerigi ---"
    if [ -d "$dd/files" ]; then
        ls -la "$dd/files/" 2>/dev/null | while IFS= read -r line; do
            log_info "  $line"
        done
    else
        log_warn "$dd/files/ dizini yok"
    fi

    # --- ARAMA STRATEJISI ---
    # Her adimda bulundugunda G_PROFILE'a yaz ve don

    # 1. Klasik konum: files/mozilla/<profil>/
    local mozilla_dir="$dd/files/mozilla"
    if [ -d "$mozilla_dir" ]; then
        log_info "files/mozilla/ bulundu"

        # 1a. profiles.ini
        if [ -f "$mozilla_dir/profiles.ini" ]; then
            log_info "profiles.ini bulundu, okunuyor..."
            local ini_path
            ini_path=$(grep "^Path=" "$mozilla_dir/profiles.ini" 2>/dev/null | head -1 | cut -d= -f2 | tr -d '\r\n ')
            if [ -n "$ini_path" ] && [ -d "$mozilla_dir/$ini_path" ]; then
                G_PROFILE="$mozilla_dir/$ini_path"
                log_ok "Profil (profiles.ini): $G_PROFILE"
                return 0
            fi
        fi

        # 1b. *.default* glob
        for d in "$mozilla_dir"/*/; do
            [ ! -d "$d" ] && continue
            local dname
            dname=$(basename "$d")
            case "$dname" in
                *.default*|*.svc*|*.release*|*.nightly*)
                    G_PROFILE="${d%/}"
                    log_ok "Profil (glob): $G_PROFILE"
                    return 0
                    ;;
            esac
        done

        # 1c. places.sqlite iceren dizin
        for d in "$mozilla_dir"/*/; do
            [ ! -d "$d" ] && continue
            if [ -f "${d}places.sqlite" ]; then
                G_PROFILE="${d%/}"
                log_ok "Profil (places.sqlite): $G_PROFILE"
                return 0
            fi
        done

        # 1d. Herhangi bir alt dizin
        for d in "$mozilla_dir"/*/; do
            if [ -d "$d" ]; then
                G_PROFILE="${d%/}"
                log_warn "Profil (ilk dizin): $G_PROFILE"
                return 0
            fi
        done
    fi

    # 2. Alternatif: GeckoView profili farkli yerde olabilir
    #    Bazi fork'lar files/geckoview/ veya dogrudan files/ kullanir
    log_info "files/mozilla/ yok veya bos, alternatif araniyor..."

    # 2a. files/ altinda herhangi bir yerde places.sqlite ara
    if [ -d "$dd/files" ]; then
        local found
        found=$(find "$dd/files" -name "places.sqlite" -type f 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            G_PROFILE=$(dirname "$found")
            log_ok "Profil (find places.sqlite): $G_PROFILE"
            return 0
        fi
    fi

    # 2b. files/ altinda herhangi bir yerde prefs.js ara
    if [ -d "$dd/files" ]; then
        local found
        found=$(find "$dd/files" -name "prefs.js" -type f 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            G_PROFILE=$(dirname "$found")
            log_ok "Profil (find prefs.js): $G_PROFILE"
            return 0
        fi
    fi

    # 2c. files/ altinda herhangi bir yerde key4.db ara
    if [ -d "$dd/files" ]; then
        local found
        found=$(find "$dd/files" -name "key4.db" -type f 2>/dev/null | head -1)
        if [ -n "$found" ]; then
            G_PROFILE=$(dirname "$found")
            log_ok "Profil (find key4.db): $G_PROFILE"
            return 0
        fi
    fi

    # 2d. Tum /data/data/<pkg> altinda ara
    local found
    found=$(find "$dd" -name "places.sqlite" -type f 2>/dev/null | head -1)
    if [ -n "$found" ]; then
        G_PROFILE=$(dirname "$found")
        log_ok "Profil (genis arama): $G_PROFILE"
        return 0
    fi

    # 3. Bulunamadi — hata ayiklama bilgisi goster
    log_error "PROFIL BULUNAMADI: $pkg"
    log_info "--- $dd tam icerigi (ilk 50 satir) ---"
    find "$dd" -type f 2>/dev/null | head -50 | while IFS= read -r line; do
        log_info "  $line"
    done
    log_info "--- Bitti ---"

    return 1
}

# ============================================================
#  HEDEF PROFİL GARANTİSİ
# ============================================================
ensure_dst_profile() {
    local pkg="$1"

    # Mevcut profili ara
    find_gecko_profile "$pkg"

    if [ -n "$G_PROFILE" ]; then
        log_ok "Hedef profil mevcut: $G_PROFILE"
        return 0
    fi

    # Profil yok — tarayiciyi baslat, profil olusturmasini bekle
    log_info "Hedef profil yok, tarayici baslatiliyor..."

    local intent
    intent=$(cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
    if [ -n "$intent" ]; then
        log_info "am start -n $intent"
        am start -n "$intent" >/dev/null 2>&1
    else
        log_info "monkey ile baslatiliyor"
        monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    fi

    log_info "10 saniye bekleniyor..."
    sleep 10
    stop_pkg "$pkg"
    sleep 2

    # Tekrar ara
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
    log_info "  Gecko Goc v3"
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

    find_gecko_profile "$SRC"
    local src_profile="$G_PROFILE"

    if [ -z "$src_profile" ]; then
        log_error "KAYNAK PROFIL BULUNAMADI!"
        exit 1
    fi
    log_ok "Kaynak profil: $src_profile"
    local src_name=$(basename "$src_profile")

    check_disk "$src_profile"

    # ---- FAZA 1: Hedef ----
    log_info "FAZA 1: HEDEF PROFIL"

    ensure_dst_profile "$DST"
    local dst_profile="$G_PROFILE"

    if [ -z "$dst_profile" ]; then
        log_error "HEDEF PROFIL HAZIRLANAMAADI!"
        exit 1
    fi
    log_ok "Hedef profil: $dst_profile"
    local dst_name=$(basename "$dst_profile")

    # Yedek
    log_info "Hedef yedekleniyor..."
    mkdir -p "${BACKUP_DIR}/target_original_profile" 2>/dev/null
    cp -rf "$dst_profile/." "${BACKUP_DIR}/target_original_profile/" 2>/dev/null
    save_manifest "$DST" "GECKO" "$dst_profile"
    log_ok "Yedek: $BACKUP_DIR"

    stop_pkg "$DST"
    sleep 1

    # ---- FAZA 2: Cekirdek Veri ----
    log_info "FAZA 2: CEKIRDEK VERI KOPYALAMA"

    for f in \
        places.sqlite places.sqlite-wal places.sqlite-shm \
        favicons.sqlite favicons.sqlite-wal favicons.sqlite-shm \
        logins.json key4.db \
        formhistory.sqlite \
        cookies.sqlite cookies.sqlite-wal cookies.sqlite-shm \
        cert9.db permissions.sqlite content-prefs.sqlite \
        webappsstore.sqlite \
    ; do
        safe_cp "${src_profile}/${f}" "${dst_profile}/${f}" "$f"
    done

    # ---- FAZA 3: Eklentiler ----
    log_info "FAZA 3: EKLENTI GOCU"

    safe_cp "${src_profile}/extensions" "${dst_profile}/extensions" "extensions/"
    safe_cp "${src_profile}/extensions.json" "${dst_profile}/extensions.json" "extensions.json"
    safe_cp "${src_profile}/extension-preferences.json" "${dst_profile}/extension-preferences.json" "extension-preferences.json"
    safe_cp "${src_profile}/storage-sync-v2.sqlite" "${dst_profile}/storage-sync-v2.sqlite" "storage-sync-v2.sqlite"
    safe_cp "${src_profile}/browser-extension-data" "${dst_profile}/browser-extension-data" "browser-extension-data/"

    # extensions.json yol yamalama
    if [ -f "${dst_profile}/extensions.json" ]; then
        log_info "extensions.json yollari yamalaniyor..."

        # Kaynak veri dizini koku
        local src_dd=""
        G_DATA_DIR=""
        resolve_data_dir "$SRC"
        src_dd="$G_DATA_DIR"

        # Hedef veri dizini koku
        local dst_dd=""
        G_DATA_DIR=""
        resolve_data_dir "$DST"
        dst_dd="$G_DATA_DIR"

        if [ -n "$src_dd" ] && [ -n "$dst_dd" ]; then
            # Tam profil yolu
            sed -i "s|${src_dd}/files/mozilla/${src_name}|${dst_dd}/files/mozilla/${dst_name}|g" \
                "${dst_profile}/extensions.json" 2>/dev/null
            # Genel veri dizini
            sed -i "s|${src_dd}/|${dst_dd}/|g" \
                "${dst_profile}/extensions.json" 2>/dev/null
            # Paket adi
            sed -i "s|${SRC}|${DST}|g" \
                "${dst_profile}/extensions.json" 2>/dev/null
            log_ok "extensions.json yamalandi"
        fi
    fi

    # UUID senkronizasyonu
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
                local tmp="${dst_prefs}.tmp.$$"
                grep -v "\"${pn}\"" "$dst_prefs" > "$tmp" 2>/dev/null
                mv -f "$tmp" "$dst_prefs" 2>/dev/null
                echo "$pl" >> "$dst_prefs"
                log_ok "Pref: $pn"
            fi
        done
    else
        log_warn "Kaynak prefs.js yok"
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

    # ---- FAZA 4: Temizlik ----
    log_info "FAZA 4: TEMIZLIK"

    rm -f  "${dst_profile}/addonStartup.json.lz4" 2>/dev/null
    rm -rf "${dst_profile}/startupCache" 2>/dev/null
    rm -f  "${dst_profile}/compatibility.ini" 2>/dev/null

    for sf in sessionstore.jsonlz4 sessionstore-backups \
              sessionstore.js sessionstore.bak; do
        rm -rf "${dst_profile}/${sf}" 2>/dev/null
    done
    log_ok "Onbellek ve session temizlendi"

    # Hedef cache
    resolve_data_dir "$DST"
    local dst_dd="$G_DATA_DIR"
    if [ -n "$dst_dd" ]; then
        rm -rf "${dst_dd}/cache" 2>/dev/null
        rm -rf "${dst_dd}/cache2" 2>/dev/null
    fi

    # Sahiplik
    local uid=$(get_uid "$DST")
    if [ -n "$uid" ]; then
        # Profil dizininin ust klasorundan itibaren duzelt
        local mozilla_parent=$(dirname "$(dirname "$dst_profile")")
        fix_perms "$mozilla_parent" "$uid"
    else
        log_error "UID alinamadi!"
    fi

    stop_pkg "$DST"

    log_info "============================================"
    log_ok   "GECKO GOCU TAMAMLANDI!"
    log_info "Yedek: $BACKUP_DIR"
    log_info "============================================"
}

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "Kullanim: $0 <kaynak> <hedef>" >&2
    exit 1
fi

main
