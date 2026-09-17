#!/bin/sh

# Xcode Cloud post-clone step for the Capacitor iOS app (capacitor-ios-spec §D.4),
# modeled on flutter_frontend/ios/ci_scripts/ci_post_clone.sh (the v1.0 line).
# Runs on a clean clone before dependency resolution; default cwd is this
# ci_scripts directory. It must (1) install Node — Xcode Cloud images ship
# Homebrew but NOT Node, (2) restore the gitignored GoogleService-Info.plist
# from a workflow secret, (3) build the static native export and sync it into
# the Xcode project, (4) install Pods (web/ios/App/Pods is NOT tracked in git),
# and (5) assert the §F.2 release guard: no /dev fixture routes in the
# shippable assets.
#
# Required Xcode Cloud workflow environment variables:
#   GOOGLE_SERVICE_INFO_PLIST_BASE64  (secret) — base64 of the After Duty
#       Firebase iOS app's GoogleService-Info.plist (BUNDLE_ID com.afterduty.app,
#       Firebase app 1:1048958573080:ios:614ae2267385cc89f8416d). It is NOT the
#       old VA Claim Path plist — the script asserts the bundle id below because
#       a mismatched plist builds fine and then breaks phone sign-in at runtime.
#       The plist is a required Copy-Bundle-Resources input; the build fails
#       without it.
#   NEXT_PUBLIC_FIREBASE_API_KEY, NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
#   NEXT_PUBLIC_FIREBASE_PROJECT_ID, NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
#   NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID, NEXT_PUBLIC_FIREBASE_APP_ID
#       — the public Firebase web config inlined into the export at build time
#       (web/.env.example; plain env vars, not secrets).
#
# Optional (committed public defaults below — web/.env.example / SETUP.md §4):
#   NEXT_PUBLIC_API_BASE           — defaults to the production Spring origin.
#   NEXT_PUBLIC_REVENUECAT_IOS_KEY — public RevenueCat SDK key.

set -e

NODE_MAJOR=26

# CI_PRIMARY_REPOSITORY_PATH is set on Xcode Cloud; the fallback makes the
# script runnable locally for dry-runs (this file lives at web/ios/App/ci_scripts/).
REPO="${CI_PRIMARY_REPOSITORY_PATH:-$(cd "$(dirname "$0")/../../../.." && pwd)}"
WEB="$REPO/web"

echo "→ Node toolchain (pinned major: ${NODE_MAJOR})"
if ! command -v node >/dev/null 2>&1; then
  # Prefer the versioned Homebrew formula; while Node ${NODE_MAJOR} is still
  # the "current" (non-LTS) line only the rolling `node` formula carries it,
  # so fall back to that.
  brew install "node@${NODE_MAJOR}" 2>/dev/null \
    && brew link --overwrite --force "node@${NODE_MAJOR}" \
    || brew install node
fi
echo "  node $(node --version) / npm $(npm --version)"
INSTALLED_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$INSTALLED_MAJOR" -lt "$NODE_MAJOR" ]; then
  echo "✗ Node ${NODE_MAJOR}+ is required (got $(node --version))."
  exit 1
elif [ "$INSTALLED_MAJOR" -ne "$NODE_MAJOR" ]; then
  echo "  ⚠ Node major ${INSTALLED_MAJOR} != pinned ${NODE_MAJOR} (rolling formula moved on) — proceeding."
fi

echo "→ Restoring GoogleService-Info.plist from workflow secret"
if [ -n "$GOOGLE_SERVICE_INFO_PLIST_BASE64" ]; then
  echo "$GOOGLE_SERVICE_INFO_PLIST_BASE64" | base64 --decode \
    > "$WEB/ios/App/App/GoogleService-Info.plist"
  echo "  wrote web/ios/App/App/GoogleService-Info.plist"
  # The plist must belong to the app being built: a VA Claim Path plist still
  # compiles into an After Duty archive, but FirebaseAuth then fails phone
  # verification on device. Catch it here, not in TestFlight.
  EXPECTED_BUNDLE_ID="$(sed -nE 's/.*PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);.*/\1/p' "$WEB/ios/App/App.xcodeproj/project.pbxproj" | head -1)"
  PLIST_BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :BUNDLE_ID' "$WEB/ios/App/App/GoogleService-Info.plist" 2>/dev/null || true)"
  if [ "$PLIST_BUNDLE_ID" != "$EXPECTED_BUNDLE_ID" ]; then
    echo "✗ GoogleService-Info.plist is for '$PLIST_BUNDLE_ID' but the target builds '$EXPECTED_BUNDLE_ID'."
    echo "  Re-encode the After Duty plist (Firebase app 'After Duty (iOS)') into the"
    echo "  GOOGLE_SERVICE_INFO_PLIST_BASE64 workflow secret."
    exit 1
  fi
  echo "  plist bundle id matches target ($PLIST_BUNDLE_ID)"
else
  echo "✗ GOOGLE_SERVICE_INFO_PLIST_BASE64 is not set — the iOS build will fail."
  echo "  Add it to this workflow as a SECRET environment variable in Xcode Cloud:"
  echo "  base64 of the After Duty (com.afterduty.app) GoogleService-Info.plist."
  exit 1
fi

echo "→ Public Firebase build config (committed defaults; override via workflow env if needed)"
# The Firebase WEB config is publishable client config — it is inlined into the web
# bundle, restricted by Firebase security rules + Authorized Domains, and is NOT a
# secret. So we default it here (private repo) exactly like NEXT_PUBLIC_API_BASE /
# NEXT_PUBLIC_REVENUECAT_IOS_KEY below, rather than requiring six Xcode Cloud
# workflow env vars. Any of these can still be overridden by a workflow env var.
export NEXT_PUBLIC_FIREBASE_API_KEY="${NEXT_PUBLIC_FIREBASE_API_KEY:-AIzaSyCyCcBHPn7lF3EKVIoaE5AtBvWiFWrHD1w}"
export NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN="${NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN:-craftloop-va-claim.firebaseapp.com}"
export NEXT_PUBLIC_FIREBASE_PROJECT_ID="${NEXT_PUBLIC_FIREBASE_PROJECT_ID:-craftloop-va-claim}"
export NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET="${NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET:-craftloop-va-claim.firebasestorage.app}"
export NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID="${NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID:-1048958573080}"
export NEXT_PUBLIC_FIREBASE_APP_ID="${NEXT_PUBLIC_FIREBASE_APP_ID:-1:1048958573080:web:b7aad2ea54869ba6f8416d}"

# Committed public defaults (web/.env.example / SETUP.md §4).
export NEXT_PUBLIC_API_BASE="${NEXT_PUBLIC_API_BASE:-https://api.afterduty.app/api}"
export NEXT_PUBLIC_REVENUECAT_IOS_KEY="${NEXT_PUBLIC_REVENUECAT_IOS_KEY:-appl_XuvonHELPKuXURtGzVeGYQklSPF}"
echo "  API base: $NEXT_PUBLIC_API_BASE"

cd "$WEB"

echo "→ npm ci (web/)"
if [ ! -f .npmrc ]; then
  echo "✗ web/.npmrc (legacy-peer-deps) is missing from the clone — npm ci would fail on peer deps."
  exit 1
fi
npm ci

echo "→ Native release export (npm run build:native — asserts no out/dev, no out/api, no Stripe markers)"
npm run build:native

echo "→ Syncing the export into the Xcode project (npx cap sync ios)"
npx cap sync ios

echo "→ pod install (web/ios/App/Pods is not tracked in git)"
cd "$WEB/ios/App"
pod install

echo "→ RELEASE GUARD (§F.2): asserting dev fixtures did not leak into shippable assets"
for LEAK in "$WEB/out/dev" "$WEB/ios/App/App/public/dev"; do
  if [ -e "$LEAK" ]; then
    echo "✗ RELEASE GUARD FAILED: $LEAK exists — /dev fixture routes leaked into the release export."
    echo "  Release archives must never contain the capture-only fixture routes."
    exit 1
  fi
done
echo "  clean — no dev/ in out/ or in ios/App/App/public/"

echo "✓ ci_post_clone complete"
