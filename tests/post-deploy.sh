#!/bin/bash
# After Duty Post-Deployment Smoke Test
#
# Run after every Cloud Run deploy to catch basic breakage like:
#   - Wrong API URL baked into the frontend bundle (#1 cause of "Failed to fetch")
#   - Backend health/auth endpoints broken
#   - CORS misconfigured
#   - Frontend not serving
#
# Usage:
#   ./tests/post-deploy.sh                                   # api.afterduty.app / app.afterduty.app
#   ./tests/post-deploy.sh API_URL WEB_URL                   # custom URLs (no trailing slash)
#
# Examples:
#   ./tests/post-deploy.sh https://api.afterduty.app \
#                          https://app.afterduty.app
#
# Exits 0 on pass, non-zero on any failure.

set -u

API_URL="${1:-https://api.afterduty.app}"
WEB_URL="${2:-https://app.afterduty.app}"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

PASS=0
FAIL=0
FAILED_TESTS=()

pass() {
    echo -e "${GREEN}OK${NC}  $1"
    PASS=$((PASS+1))
}

fail() {
    echo -e "${RED}FAIL${NC} $1"
    [ -n "${2:-}" ] && echo "       $2"
    FAIL=$((FAIL+1))
    FAILED_TESTS+=("$1")
}

echo "================================================="
echo "After Duty Post-Deploy Smoke Test"
echo "API: $API_URL"
echo "Web: $WEB_URL"
echo "================================================="

# 1. Backend health (with retry to absorb Cloud Run cold-start after a fresh deploy)
HEALTH=""
for attempt in 1 2 3 4 5; do
    HEALTH=$(curl -fsS --max-time 20 "$API_URL/health" 2>/dev/null || echo "")
    if echo "$HEALTH" | grep -q '"service"'; then
        break
    fi
    sleep 3
done
if echo "$HEALTH" | grep -q '"service"'; then
    pass "Backend /health responds"
else
    fail "Backend /health responds" "got: $HEALTH (after 5 attempts)"
fi

# 2. Backend rejects unauth on /api/auth/me with 401 (NOT 404 — would mean wrong service)
STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$API_URL/api/auth/me")
if [ "$STATUS" = "401" ] || [ "$STATUS" = "403" ]; then
    pass "Backend /api/auth/me returns auth error (got $STATUS)"
else
    fail "Backend /api/auth/me returns auth error" "expected 401/403, got $STATUS"
fi

# 3. CORS preflight from the deployed frontend origin
STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 -X OPTIONS \
    -H "Origin: $WEB_URL" \
    -H "Access-Control-Request-Method: GET" \
    -H "Access-Control-Request-Headers: authorization,x-user-email" \
    "$API_URL/api/auth/me")
if [ "$STATUS" = "200" ] || [ "$STATUS" = "204" ]; then
    pass "CORS preflight from $WEB_URL allowed (got $STATUS)"
else
    fail "CORS preflight from $WEB_URL allowed" "expected 200/204, got $STATUS"
fi

# 4. Frontend root reachable. Anonymous "/" is a 307 to /login (the Next auth
# gate) — follow redirects; the destination must land 200.
STATUS=$(curl -sL -o /dev/null -w "%{http_code}" --max-time 15 "$WEB_URL/")
if [ "$STATUS" = "200" ]; then
    pass "Frontend root reachable (after auth-gate redirect)"
else
    fail "Frontend root reachable (after auth-gate redirect)" "got $STATUS"
fi

# 4b. /api/usage requires auth and returns the right shape under dev mode
STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$API_URL/api/usage")
if [ "$STATUS" = "401" ] || [ "$STATUS" = "403" ]; then
    pass "Usage endpoint requires auth (got $STATUS)"
else
    fail "Usage endpoint requires auth" "expected 401/403, got $STATUS"
fi

# Optional: if X-User-Email auth is enabled in this environment, check the shape.
SHAPE=$(curl -s --max-time 15 -H "X-User-Email: post-deploy-smoke@example.com" "$API_URL/api/usage")
if echo "$SHAPE" | grep -q '"percentUsed"'; then
    pass "Usage endpoint returns percentUsed shape"
fi

# 5. Frontend serves the Next.js app (successor to the retired Flutter
# main.dart.js check — see docs/maintenance/dead-code-audit-2026-08-02.md).
# /login is the unauthenticated page; it must render Next HTML, and must not
# reference localhost (the regression class the old check existed to catch).
PAGE_TMP=$(mktemp)
trap "rm -f $PAGE_TMP" EXIT

if curl -fsSL --max-time 60 "$WEB_URL/login" -o "$PAGE_TMP"; then
    if grep -q "/_next/" "$PAGE_TMP"; then
        pass "Frontend serves Next.js HTML (login page)"
    else
        fail "Frontend serves Next.js HTML (login page)" "no /_next/ asset refs in response"
    fi
    if grep -q "localhost:8080\|localhost:3000" "$PAGE_TMP"; then
        fail "Frontend HTML does not reference localhost" "dev URL leaked into the deployed page"
    else
        pass "Frontend HTML does not reference localhost"
    fi
else
    fail "Frontend login page fetched" "curl failed for $WEB_URL/login"
fi

# 6. Next build id is present in the page (identifies the deployed build).
# The id is the /_next/static/<id>/ segment that is not a fixed asset dir.
BUILD_ID=$(grep -o '/_next/static/[^/"]*/' "$PAGE_TMP" 2>/dev/null | cut -d/ -f4 | grep -vE '^(chunks|css|media)$' | head -1)
if [ -n "$BUILD_ID" ]; then
    pass "Frontend Next build id present: $BUILD_ID"
else
    # Not fatal — informational only
    echo "      (Next build id not found in page — informational only)"
fi

# Published contact addresses must actually receive mail. A rebrand can leave the
# copy internally consistent while pointing users at mailboxes nobody created.
if EMAIL_OUT=$("$(dirname "$0")/check_published_emails.py" 2>&1); then
    pass "Published contact addresses accept mail"
    [ -n "$EMAIL_OUT" ] && echo "$EMAIL_OUT"
else
    fail "Published contact addresses reject mail" "$EMAIL_OUT"
fi

echo ""
echo "================================================="
echo -e "Results: ${GREEN}$PASS passed${NC}, ${RED}$FAIL failed${NC}"
echo "================================================="

if [ $FAIL -gt 0 ]; then
    echo -e "${RED}Failed checks:${NC}"
    for t in "${FAILED_TESTS[@]}"; do
        echo "  - $t"
    done
    exit 1
fi

exit 0
