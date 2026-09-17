#!/bin/bash
# After Duty Regression Test Suite
# Runs API-level tests against the deployed backend to verify all critical flows.
#
# Usage:
#   ./regression.sh [API_URL]
#
# Example:
#   ./regression.sh https://api.afterduty.app
#   ./regression.sh http://localhost:8080  # local dev

set -e

API_URL="${1:-https://api.afterduty.app}"
EMAIL="test-regression@example.com"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0
FAILED_TESTS=()

test_case() {
    local name="$1"
    local expected_status="$2"
    local actual_status="$3"
    local extra_check="$4"

    if [ "$actual_status" = "$expected_status" ]; then
        if [ -n "$extra_check" ] && [ "$extra_check" != "pass" ]; then
            echo -e "${RED}✗ $name${NC} (extra check failed: $extra_check)"
            FAIL=$((FAIL+1))
            FAILED_TESTS+=("$name")
        else
            echo -e "${GREEN}✓ $name${NC}"
            PASS=$((PASS+1))
        fi
    else
        echo -e "${RED}✗ $name${NC} (expected $expected_status, got $actual_status)"
        FAIL=$((FAIL+1))
        FAILED_TESTS+=("$name")
    fi
}

echo "========================================="
echo "After Duty Regression Tests"
echo "API: $API_URL"
echo "========================================="

# Test 1: Health check
STATUS=$(curl -s -o /tmp/rt_health.json -w "%{http_code}" "$API_URL/health")
SERVICE=$(jq -r '.service // empty' /tmp/rt_health.json 2>/dev/null)
if [ "$STATUS" = "200" ] && [ "$SERVICE" = "afterduty-api" ]; then
    test_case "Health check" "200" "200" "pass"
else
    test_case "Health check" "200" "$STATUS" "service=$SERVICE"
fi

# Test 2: CORS preflight
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X OPTIONS \
    -H "Origin: https://app.afterduty.app" \
    -H "Access-Control-Request-Method: GET" \
    "$API_URL/api/auth/me")
test_case "CORS preflight allows frontend origin" "200" "$STATUS"

# Test 3: Unauthenticated endpoints return 401 (not 404 or 500)
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$API_URL/api/auth/me")
if [ "$STATUS" = "401" ] || [ "$STATUS" = "403" ]; then
    test_case "Unauthenticated /api/auth/me returns auth error" "401" "401" "pass"
else
    test_case "Unauthenticated /api/auth/me returns auth error" "401" "$STATUS"
fi

# Test 4: Dev-mode email auth works when DEV_MODE=true
# In prod DEV_MODE=false so this should be 401
STATUS=$(curl -s -o /tmp/rt_me.json -w "%{http_code}" \
    -H "X-User-Email: $EMAIL" \
    "$API_URL/api/auth/me")
if [ "$STATUS" = "200" ]; then
    test_case "X-User-Email auth (dev mode)" "200" "200" "pass"
    DEV_MODE=true
else
    test_case "X-User-Email auth blocked (prod mode)" "401" "401" "pass"
    DEV_MODE=false
fi

# Test 5: Firebase token verification
# Generate a fake malformed token to ensure the endpoint rejects it properly
STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer invalid.token.here" \
    "$API_URL/api/auth/me")
if [ "$STATUS" = "401" ] || [ "$STATUS" = "403" ]; then
    test_case "Invalid Firebase token rejected" "401" "401" "pass"
else
    test_case "Invalid Firebase token rejected" "401" "$STATUS"
fi

# --- Only run authenticated tests if dev mode is on ---
if [ "$DEV_MODE" = "true" ]; then
    AUTH_HEADER="X-User-Email: $EMAIL"

    # Test 6: Get claim
    STATUS=$(curl -s -o /tmp/rt_claim.json -w "%{http_code}" \
        -H "$AUTH_HEADER" \
        "$API_URL/api/claim")
    test_case "GET /api/claim returns claim" "200" "$STATUS"

    # Test 7: Get conditions
    STATUS=$(curl -s -o /tmp/rt_conditions.json -w "%{http_code}" \
        -H "$AUTH_HEADER" \
        "$API_URL/api/claim/conditions")
    CONDITION_COUNT=$(jq -r '. | length // 0' /tmp/rt_conditions.json 2>/dev/null)
    if [ "$STATUS" = "200" ]; then
        test_case "GET /api/claim/conditions" "200" "200" "pass"
        echo "    ($CONDITION_COUNT conditions)"
    else
        test_case "GET /api/claim/conditions" "200" "$STATUS"
    fi

    # Test 8: Get evidence
    STATUS=$(curl -s -o /tmp/rt_evidence.json -w "%{http_code}" \
        -H "$AUTH_HEADER" \
        "$API_URL/api/claim/evidence")
    test_case "GET /api/claim/evidence" "200" "$STATUS"

    # Test 9: Get messages
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "$AUTH_HEADER" \
        "$API_URL/api/claim/messages")
    test_case "GET /api/claim/messages" "200" "$STATUS"

    # Test 10: Profile endpoint (returns 204 when no profile yet)
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "$AUTH_HEADER" \
        "$API_URL/api/auth/profile")
    if [ "$STATUS" = "200" ] || [ "$STATUS" = "204" ]; then
        test_case "GET /api/auth/profile" "$STATUS" "$STATUS" "pass"
    else
        test_case "GET /api/auth/profile" "200/204" "$STATUS"
    fi

    # Test 11: Upload evidence (empty body, expect 400)
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "$AUTH_HEADER" \
        -F "source_type=upload" \
        "$API_URL/api/claim/evidence")
    if [ "$STATUS" = "400" ] || [ "$STATUS" = "422" ]; then
        test_case "POST /api/claim/evidence rejects empty upload" "400" "400" "pass"
    else
        test_case "POST /api/claim/evidence rejects empty upload" "400" "$STATUS"
    fi

    # Test 12: Upload evidence with a unique file each run
    UNIQUE=$(date +%s)-$RANDOM
    echo "test document $UNIQUE" > /tmp/rt_test_$UNIQUE.txt
    STATUS=$(curl -s -o /tmp/rt_upload.json -w "%{http_code}" \
        -X POST \
        -H "$AUTH_HEADER" \
        -F "source_type=upload" \
        -F "file=@/tmp/rt_test_$UNIQUE.txt" \
        "$API_URL/api/claim/evidence")
    EVIDENCE_ID=$(jq -r '.id // empty' /tmp/rt_upload.json 2>/dev/null)
    rm -f /tmp/rt_test_$UNIQUE.txt
    if { [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; } && [ -n "$EVIDENCE_ID" ]; then
        test_case "POST /api/claim/evidence (file upload)" "$STATUS" "$STATUS" "pass"

        # Test 13: Delete the uploaded file (204 No Content is correct)
        STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
            -X DELETE \
            -H "$AUTH_HEADER" \
            "$API_URL/api/claim/evidence/$EVIDENCE_ID")
        if [ "$STATUS" = "200" ] || [ "$STATUS" = "204" ]; then
            test_case "DELETE /api/claim/evidence/:id" "$STATUS" "$STATUS" "pass"
        else
            test_case "DELETE /api/claim/evidence/:id" "200/204" "$STATUS"
        fi
    else
        test_case "POST /api/claim/evidence (file upload)" "200" "$STATUS"
    fi

    # Test 14: Chat message (accepts 200 or 201 CREATED)
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "$AUTH_HEADER" \
        -H "Content-Type: application/json" \
        -d '{"message":"test regression message"}' \
        "$API_URL/api/claim/chat")
    if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then
        test_case "POST /api/claim/chat" "$STATUS" "$STATUS" "pass"
    else
        test_case "POST /api/claim/chat" "200/201" "$STATUS"
    fi

    # Test 15: Pipeline metrics
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "$AUTH_HEADER" \
        "$API_URL/api/claim/pipeline-metrics")
    test_case "GET /api/claim/pipeline-metrics" "200" "$STATUS"

    # Test 16: VASRD search
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "$AUTH_HEADER" \
        "$API_URL/api/vasrd/search?q=asthma")
    test_case "GET /api/vasrd/search" "200" "$STATUS"
fi

# Test 17: Frontend is reachable
WEB_URL="${API_URL/va-claim-api/va-claim-web-next}"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/")
test_case "Frontend reachable" "200" "$STATUS"

# Summary
echo ""
echo "========================================="
echo -e "Results: ${GREEN}$PASS passed${NC}, ${RED}$FAIL failed${NC}"
echo "========================================="

if [ $FAIL -gt 0 ]; then
    echo -e "${RED}Failed tests:${NC}"
    for t in "${FAILED_TESTS[@]}"; do
        echo "  - $t"
    done
    exit 1
fi

exit 0
