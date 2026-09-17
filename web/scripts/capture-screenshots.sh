#!/usr/bin/env bash
# App Store screenshot pipeline (capacitor-ios-spec §F.3 — "the deliverable").
#
# Boots the required iOS simulators, deep-links into the /dev/* fixture screens
# via the afterduty:// custom scheme, shoots each with `xcrun simctl io …
# screenshot`, and ASSERTS the exact App Store pixel dimensions per device
# (1320×2868 for the 6.9" iPhone, 2064×2752 for the 13" iPad). Output mirrors the
# manual-upload layout under web/screenshots/out/<set>/ (gitignored). Upload to
# ASC stays MANUAL — automation can't drag host files into ASC (§F.1).
#
# This is LOCAL tooling: it never touches the shipping binary, the web target, or
# the SACRED `tsc && vitest && build` gate. It requires a Mac GUI + Xcode + the
# simulators below, so it is hand-run by the release owner, not CI.
#
# Usage:
#   web/scripts/capture-screenshots.sh           # build capture bundle + .app, then capture
#   SKIP_BUILD=1 web/scripts/capture-screenshots.sh   # reuse an existing .app (faster reruns)
#
# Prereqs (per §F.1): Xcode with these simulators installed —
#   "iPhone 17 Pro Max" (iOS 26.5)   → 1320×2868
#   "iPad Pro 13-inch (M5)"          → 2064×2752

set -euo pipefail

# ── Locations ────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$(dirname "$SCRIPT_DIR")"
IOS_APP_DIR="$WEB_DIR/ios/App"
OUT_ROOT="$WEB_DIR/screenshots/out"
BUILD_DIR="$WEB_DIR/screenshots/.build"        # DerivedData for the capture .app
APP_BUNDLE_ID="com.afterduty.app"

# ── Devices: "<simulator name>:<output set>:<expectW>:<expectH>" ─────────────
# Output set + expected pixel dimensions are the App Store listing conventions
# (§F.1). A mismatch fails the run loudly (wrong sim / scaled capture).
DEVICES=(
  "iPhone 17 Pro Max:iphone69:1320:2868"
  "iPad Pro 13-inch (M5):ipad-13:2064:2752"
)

# ── Screens: "<slot>:<dev-route path>" ───────────────────────────────────────
# 7 screens captured; the 5 uploaded per slot are chosen at upload time (§F.3).
# NOTE: condition-detail points at /dev/conditions/93 — a REAL fixture id from
# populatedHomeVM.conditions (93/94/95/98/99/102). The spec text's `/dev/conditions/1`
# does not exist in the capture export (no out/dev/conditions/1.html), so it is
# reconciled here to the lowest real fixture id (review finding, §F.3).
SCREENS=(
  "00-home:/dev/home/populated"
  "01-conditions:/dev/conditions"
  "02-condition-detail:/dev/conditions/93"
  "03-steps:/dev/steps"
  "04-documents:/dev/documents"
  "05-ask:/dev/ask"
  "06-share:/dev/share"
  "07-upgrade:/dev/upgrade"          # native paywall — IAP review screenshot (§F.4)
)

RENDER_SETTLE="${RENDER_SETTLE:-2.5}"  # seconds for render + skeleton settle (§F.3)
FIRST_SETTLE="${FIRST_SETTLE:-7}"      # seconds after a cold launch before the first deep link

log() { printf '\033[1;36m▶ %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ── 1. Build the capture bundle + sync into iOS, then build the .app ─────────
APP_PATH=""
build_app() {
  log "Building SCREENSHOT capture bundle (build:native:capture) + cap sync"
  ( cd "$WEB_DIR" && npm run build:native:capture && npx cap sync ios )

  log "Building the capture .app for the simulator (xcodebuild)"
  rm -rf "$BUILD_DIR"
  xcodebuild \
    -workspace "$IOS_APP_DIR/App.xcworkspace" \
    -scheme App \
    -configuration Debug \
    -sdk iphonesimulator \
    -derivedDataPath "$BUILD_DIR" \
    -destination 'generic/platform=iOS Simulator' \
    build \
    | tail -5

  APP_PATH="$(/usr/bin/find "$BUILD_DIR/Build/Products" -maxdepth 2 -name 'App.app' -type d | head -1)"
  [ -n "$APP_PATH" ] || die "Could not locate built App.app under $BUILD_DIR"
  log "Built app: $APP_PATH"
}

find_existing_app() {
  APP_PATH="$(/usr/bin/find "$BUILD_DIR/Build/Products" -maxdepth 2 -name 'App.app' -type d 2>/dev/null | head -1)"
  [ -n "$APP_PATH" ] || die "SKIP_BUILD=1 but no prior App.app under $BUILD_DIR — run once without SKIP_BUILD."
  log "Reusing app: $APP_PATH"
}

# ── 2. Per-device capture ────────────────────────────────────────────────────
udid_for() {
  # Resolve a booted-or-available device UDID by exact name. Match the name as a
  # FIXED string ("<name> (") — device names contain regex metacharacters (e.g.
  # "iPad Pro 13-inch (M5)"), so an -E pattern would parse "(M5)" as a group and
  # never match. The " (" suffix disambiguates prefixes ("iPhone 17" vs "iPhone 17 Pro").
  xcrun simctl list devices available \
    | grep -F "$1 (" \
    | head -1 \
    | sed -E 's/.*\(([0-9A-Fa-f-]{36})\).*/\1/'
}

capture_device() {
  local name="$1" set="$2" expW="$3" expH="$4"
  local udid; udid="$(udid_for "$name")"
  [ -n "$udid" ] || die "Simulator not found: '$name' (install it in Xcode — §F.1)"
  log "Device '$name' [$udid] → set '$set' (expect ${expW}×${expH})"

  xcrun simctl boot "$udid" 2>/dev/null || true   # no-op if already booted
  xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1 || true

  # Clean status bar: 9:41, full battery/signal (§F.3). "discharging" at 100%
  # draws Apple's plain full battery; "charged" adds the charging bolt.
  xcrun simctl status_bar "$udid" override \
    --time "9:41" --batteryLevel 100 --batteryState discharging \
    --cellularBars 4 --wifiBars 3 --dataNetwork wifi

  xcrun simctl install "$udid" "$APP_PATH"

  local first=1
  for entry in "${SCREENS[@]}"; do
    local slot="${entry%%:*}" path="${entry#*:}"
    local out_dir="$OUT_ROOT/$set/$slot"
    mkdir -p "$out_dir"
    local out_file="$out_dir/ios-$slot.png"

    if [ "$first" -eq 1 ]; then
      xcrun simctl launch "$udid" "$APP_BUNDLE_ID" >/dev/null
      first=0
      # A cold launch needs longer than the per-screen settle before the
      # WebView has installed the capture build's deep-link listener — on the
      # iPhone simulator the first link otherwise arrives early and is dropped,
      # leaving the login screen in the 00-home slot. Wait, then prime with the
      # first screen's link (idempotent: the loop re-sends it below).
      sleep "$FIRST_SETTLE"
      xcrun simctl openurl "$udid" "afterduty://$path"
      sleep "$RENDER_SETTLE"
    fi

    # Deep-link to the fixture screen via the custom scheme (§F.3).
    xcrun simctl openurl "$udid" "afterduty://$path"
    sleep "$RENDER_SETTLE"
    xcrun simctl io "$udid" screenshot --type png "$out_file"

    # Assert dimensions — fail loudly on mismatch (§F.3).
    local w h
    w="$(sips -g pixelWidth "$out_file" | awk '/pixelWidth/{print $2}')"
    h="$(sips -g pixelHeight "$out_file" | awk '/pixelHeight/{print $2}')"
    if [ "$w" != "$expW" ] || [ "$h" != "$expH" ]; then
      xcrun simctl status_bar "$udid" clear || true
      die "Dimension mismatch for $out_file: got ${w}×${h}, expected ${expW}×${expH} (wrong sim or scaled capture)."
    fi
    log "  ✓ $slot → $out_file (${w}×${h})"
  done

  xcrun simctl status_bar "$udid" clear
}

main() {
  command -v xcrun >/dev/null 2>&1 || die "xcrun not found — this pipeline needs Xcode on macOS (§G.2)."
  mkdir -p "$OUT_ROOT"

  if [ "${SKIP_BUILD:-0}" = "1" ]; then
    find_existing_app
  else
    build_app
  fi

  for dev in "${DEVICES[@]}"; do
    IFS=':' read -r name set expW expH <<<"$dev"
    capture_device "$name" "$set" "$expW" "$expH"
  done

  log "Done. Screenshots under $OUT_ROOT/  (upload to ASC manually — §F.1)."
}

main "$@"
