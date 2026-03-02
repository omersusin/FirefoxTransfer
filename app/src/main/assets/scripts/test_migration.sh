#!/system/bin/sh
# ============================================================
#  test_migration.sh — Göç Sonrası Doğrulama Scripti
# ============================================================
# Kullanım: test_migration.sh <hedef_paket> <motor_tipi>
# Örnek:    test_migration.sh org.mozilla.fenix GECKO
# ============================================================

DST_PKG="$1"
ENGINE="$2"

PASS=0
FAIL=0
WARN=0

check() {
    local desc="$1"
    local condition="$2"

    if eval "$condition"; then
        echo "  ✅ PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  ❌ FAIL: $desc"
        FAIL=$((FAIL + 1))
    fi
}

warn_check() {
    local desc="$1"
    local condition="$2"

    if eval "$condition"; then
        echo "  ✅ PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  ⚠️  WARN: $desc"
        WARN=$((WARN + 1))
    fi
}

echo "============================================"
echo "  GÖÇ SONRASI DOĞRULAMA"
echo "  Paket: $DST_PKG"
echo "  Motor: $ENGINE"
echo "============================================"

if [ "$ENGINE" = "GECKO" ]; then
    MOZILLA_DIR="/data/data/${DST_PKG}/files/mozilla"
    PROFILE=$(find "$MOZILLA_DIR" -maxdepth 1 -type d -name "*.default*" 2>/dev/null | head -1)

    echo ""
    echo "--- Profil Kontrolü ---"
    check "Mozilla dizini mevcut" "[ -d '$MOZILLA_DIR' ]"
    check "Profil dizini mevcut" "[ -n '$PROFILE' ] && [ -d '$PROFILE' ]"

    echo ""
    echo "--- Çekirdek Veriler ---"
    check "places.sqlite mevcut" "[ -f '$PROFILE/places.sqlite' ]"
    check "places.sqlite boş değil" "[ -s '$PROFILE/places.sqlite' ]"
    check "favicons.sqlite mevcut" "[ -f '$PROFILE/favicons.sqlite' ]"

    echo ""
    echo "--- Şifreler ---"
    check "logins.json mevcut" "[ -f '$PROFILE/logins.json' ]"
    check "key4.db mevcut" "[ -f '$PROFILE/key4.db' ]"

    echo ""
    echo "--- Eklentiler ---"
    check "extensions/ dizini mevcut" "[ -d '$PROFILE/extensions' ]"

    echo ""
    echo "--- Temizlik Kontrolü ---"
    check "sessionstore.jsonlz4 SİLİNMİŞ" "[ ! -f '$PROFILE/sessionstore.jsonlz4' ]"
    check "addonStartup.json.lz4 SİLİNMİŞ" "[ ! -f '$PROFILE/addonStartup.json.lz4' ]"

    echo ""
    echo "--- Sahiplik Kontrolü ---"
    EXPECTED_UID=$(stat -c '%u' "/data/data/$DST_PKG" 2>/dev/null)
    ACTUAL_UID=$(stat -c '%u' "$PROFILE/places.sqlite" 2>/dev/null)
    check "Sahiplik doğru (uid=$EXPECTED_UID)" "[ '$EXPECTED_UID' = '$ACTUAL_UID' ]"

elif [ "$ENGINE" = "CHROMIUM" ]; then
    BASE_DIR=""
    for d in app_chrome app_chromium app_brave app_vivaldi; do
        if [ -d "/data/data/${DST_PKG}/${d}" ]; then
            BASE_DIR="/data/data/${DST_PKG}/${d}"
            break
        fi
    done

    PROFILE="${BASE_DIR}/Default"

    echo ""
    echo "--- Profil Kontrolü ---"
    check "Chromium base dizini mevcut" "[ -n '$BASE_DIR' ] && [ -d '$BASE_DIR' ]"
    check "Default profil mevcut" "[ -d '$PROFILE' ]"

    echo ""
    echo "--- Çekirdek Veriler ---"
    check "Bookmarks mevcut" "[ -f '$PROFILE/Bookmarks' ]"
    check "History mevcut" "[ -f '$PROFILE/History' ]"

    echo ""
    echo "--- Şifreler ---"
    check "Login Data mevcut" "[ -f '$PROFILE/Login Data' ]"

    echo ""
    echo "--- Sahiplik Kontrolü ---"
    EXPECTED_UID=$(stat -c '%u' "/data/data/$DST_PKG" 2>/dev/null)
    ACTUAL_UID=$(stat -c '%u' "$PROFILE/History" 2>/dev/null)
    check "Sahiplik doğru (uid=$EXPECTED_UID)" "[ '$EXPECTED_UID' = '$ACTUAL_UID' ]"
fi

echo ""
echo "============================================"
echo "  SONUÇ: $PASS geçti, $FAIL başarısız, $WARN uyarı"
echo "============================================"

if [ $FAIL -gt 0 ]; then
    exit 1
else
    exit 0
fi
