#!/system/bin/sh
# ============================================================
#  test_migration.sh — Post-Migration Validation Script
# ============================================================
# Usage: test_migration.sh <target_package> <engine_type>
# Example: test_migration.sh org.mozilla.fenix GECKO
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
echo "  POST-MIGRATION VALIDATION"
echo "  Package: $DST_PKG"
echo "  Engine:  $ENGINE"
echo "============================================"

if [ "$ENGINE" = "GECKO" ]; then
    MOZILLA_DIR="/data/data/${DST_PKG}/files/mozilla"
    PROFILE=$(find "$MOZILLA_DIR" -maxdepth 1 -type d -name "*.default*" 2>/dev/null | head -1)

    echo ""
    echo "--- Profile Check ---"
    check "Mozilla directory exists" "[ -d '$MOZILLA_DIR' ]"
    check "Profile directory exists" "[ -n '$PROFILE' ] && [ -d '$PROFILE' ]"

    echo ""
    echo "--- Core Data ---"
    check "places.sqlite exists" "[ -f '$PROFILE/places.sqlite' ]"
    check "places.sqlite not empty" "[ -s '$PROFILE/places.sqlite' ]"
    check "favicons.sqlite exists" "[ -f '$PROFILE/favicons.sqlite' ]"

    echo ""
    echo "--- Passwords ---"
    check "logins.json exists" "[ -f '$PROFILE/logins.json' ]"
    check "key4.db exists" "[ -f '$PROFILE/key4.db' ]"

    echo ""
    echo "--- Extensions ---"
    check "extensions/ directory exists" "[ -d '$PROFILE/extensions' ]"

    echo ""
    echo "--- Cleanup Check ---"
    check "sessionstore.jsonlz4 DELETED" "[ ! -f '$PROFILE/sessionstore.jsonlz4' ]"
    check "addonStartup.json.lz4 DELETED" "[ ! -f '$PROFILE/addonStartup.json.lz4' ]"

    echo ""
    echo "--- Ownership Check ---"
    EXPECTED_UID=$(stat -c '%u' "/data/data/$DST_PKG" 2>/dev/null)
    ACTUAL_UID=$(stat -c '%u' "$PROFILE/places.sqlite" 2>/dev/null)
    check "Ownership correct (uid=$EXPECTED_UID)" "[ '$EXPECTED_UID' = '$ACTUAL_UID' ]"

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
    echo "--- Profile Check ---"
    check "Chromium base directory exists" "[ -n '$BASE_DIR' ] && [ -d '$BASE_DIR' ]"
    check "Default profile exists" "[ -d '$PROFILE' ]"

    echo ""
    echo "--- Core Data ---"
    check "Bookmarks exists" "[ -f '$PROFILE/Bookmarks' ]"
    check "History exists" "[ -f '$PROFILE/History' ]"

    echo ""
    echo "--- Passwords ---"
    check "Login Data exists" "[ -f '$PROFILE/Login Data' ]"

    echo ""
    echo "--- Ownership Check ---"
    EXPECTED_UID=$(stat -c '%u' "/data/data/$DST_PKG" 2>/dev/null)
    ACTUAL_UID=$(stat -c '%u' "$PROFILE/History" 2>/dev/null)
    check "Ownership correct (uid=$EXPECTED_UID)" "[ '$EXPECTED_UID' = '$ACTUAL_UID' ]"
fi

echo ""
echo "============================================"
echo "  RESULT: $PASS passed, $FAIL failed, $WARN warning"
echo "============================================"

if [ $FAIL -gt 0 ]; then
    exit 1
else
    exit 0
fi
