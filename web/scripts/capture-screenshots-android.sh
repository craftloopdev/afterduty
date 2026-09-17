#!/usr/bin/env bash
# Play Store screenshot pipeline — the Android sibling of capture-screenshots.sh.
#
# Boots the named AVDs headless, forces each display to a Play-accepted aspect
# ratio (phone 9:16 portrait; tablet in its natural 16:10 landscape), installs the capture-build debug APK, deep-links into the /dev/* fixture
# screens via the afterduty:// scheme (the same deep-link mapper the iOS
# pipeline uses; capture builds allow /dev/*), shoots each with `adb screencap`,
# and ASSERTS the exact pixel dimensions. Output mirrors the iOS layout under
# web/screenshots/out/<set>/ (gitignored). Upload to Play Console stays MANUAL.
#
# Why `wm size` and not post-hoc cropping: Play rejects screenshots whose long
# side exceeds twice the short side. A Pixel-6-class 1080×2340 display (19.5:9)
# fails that; rendering the app at 1080×1920 (9:16) produces a compliant capture
# with nothing cropped or stretched. The tablet renders in its natural landscape (2560×1600, 16:10) —
# at its portrait width (800 CSS px) the desktop layout engages and the hero card overflows.
#
# Usage:
#   web/scripts/capture-screenshots-android.sh              # build capture bundle + APK, then capture
#   SKIP_BUILD=1 web/scripts/capture-screenshots-android.sh # reuse the existing debug APK
#
# Prereqs: Android SDK with `emulator`, `adb`, and these AVDs (android-35 google_apis):
#   galaxy_s24    (Pixel 6 profile) → set "phone",     1080×1920
#   pixel_tablet  (Pixel Tablet)    → set "tablet-10", 2560×1600
# The emulators may already be running; the script attaches to a booted AVD by name.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$(dirname "$SCRIPT_DIR")"
OUT_ROOT="$WEB_DIR/screenshots/out"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
APK="$WEB_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
APP_ID="com.afterduty.app"

# "<avd>:<output set>:<width>x<height>" — the forced display size IS the asserted size.
DEVICES=(
  "galaxy_s24:phone:1080x1920"
  "pixel_tablet:tablet-10:2560x1600"
)

# Same slots as the iOS pipeline so the two listings tell the same story.
SCREENS=(
  "00-home:/dev/home/populated"
  "01-conditions:/dev/conditions"
  "02-condition-detail:/dev/conditions/93"
  "03-steps:/dev/steps"
  "04-documents:/dev/documents"
  "05-ask:/dev/ask"
  "06-share:/dev/share"
)

RENDER_SETTLE="${RENDER_SETTLE:-3}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-240}"

log() { printf '\033[1;36m▶ %s\033[0m\n' "$*" >&2; }
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

build_apk() {
  log "Building SCREENSHOT capture bundle (build:native:capture) + cap sync android"
  ( cd "$WEB_DIR" && npm run build:native:capture && npx cap sync android )
  log "Assembling the debug APK"
  ( cd "$WEB_DIR/android" && ./gradlew --quiet assembleDebug )
  [ -f "$APK" ] || die "No APK at $APK"
}

# Resolve the adb serial of a running emulator by AVD name (there may be several).
serial_for_avd() {
  local want="$1" s
  for s in $("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}'); do
    if [ "$("$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$want" ]; then
      echo "$s"; return 0
    fi
  done
  return 1
}

ensure_booted() {
  local avd="$1" serial
  if serial="$(serial_for_avd "$avd")"; then
    log "AVD '$avd' already running as $serial"
  else
    log "Booting AVD '$avd' headless"
    nohup "$EMULATOR" -avd "$avd" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot >/dev/null 2>&1 &
    local waited=0
    until serial="$(serial_for_avd "$avd")"; do
      sleep 3; waited=$((waited+3))
      [ "$waited" -lt "$BOOT_TIMEOUT" ] || die "AVD '$avd' did not appear within ${BOOT_TIMEOUT}s"
    done
  fi
  "$ADB" -s "$serial" wait-for-device
  local waited=0
  until [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 3; waited=$((waited+3))
    [ "$waited" -lt "$BOOT_TIMEOUT" ] || die "AVD '$avd' did not finish booting within ${BOOT_TIMEOUT}s"
  done
  echo "$serial"
}

# Clean status bar via SystemUI demo mode: 9:41, full battery, full signal, no notifications.
status_bar_on() {
  local s="$1"
  "$ADB" -s "$s" shell settings put global sysui_demo_allowed 1
  "$ADB" -s "$s" shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
  "$ADB" -s "$s" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
  "$ADB" -s "$s" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
  # One network command per radio (a combined wifi+mobile broadcast is only
  # partially applied), and the mobile command MUST name its SIM slot: on the
  # android-35 image SystemUI's demo pipeline ignores a mobile command without
  # `slot`, and the emulator's real radio ("3G") leaks through instead of LTE.
  "$ADB" -s "$s" shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true >/dev/null
  "$ADB" -s "$s" shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e slot 0 -e datatype lte -e level 4 -e fully true >/dev/null
  "$ADB" -s "$s" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
}
status_bar_off() {
  "$ADB" -s "$1" shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
}

capture_device() {
  local avd="$1" set="$2" size="$3" expW="${3%x*}" expH="${3#*x}"
  local serial; serial="$(ensure_booted "$avd")"
  log "Device '$avd' [$serial] → set '$set' (display forced to ${expW}×${expH})"

  "$ADB" -s "$serial" shell wm size "${expW}x${expH}"
  "$ADB" -s "$serial" shell settings put system accelerometer_rotation 0
  "$ADB" -s "$serial" shell settings put system user_rotation 0
  status_bar_on "$serial"
  "$ADB" -s "$serial" install -r -t "$APK" >/dev/null

  # Warm-launch first, exactly like the iOS pipeline: with the app already up,
  # each VIEW intent below reaches the singleTask activity as onNewIntent, which
  # Capacitor surfaces as `appUrlOpen` for the capture build's root listener.
  "$ADB" -s "$serial" shell am start -W -n "$APP_ID/.MainActivity" >/dev/null
  sleep "$RENDER_SETTLE"

  for entry in "${SCREENS[@]}"; do
    local slot="${entry%%:*}" path="${entry#*:}"
    local out_dir="$OUT_ROOT/$set/$slot"; mkdir -p "$out_dir"
    local out_file="$out_dir/android-$slot.png"

    "$ADB" -s "$serial" shell am start -W -a android.intent.action.VIEW \
      -d "afterduty://$path" "$APP_ID" >/dev/null
    sleep "$RENDER_SETTLE"
    # The login screen autofocuses its input; if a soft keyboard is still up
    # after navigating away, dismiss it (no-op otherwise).
    "$ADB" -s "$serial" shell input keyevent KEYCODE_ESCAPE
    sleep 0.5
    "$ADB" -s "$serial" exec-out screencap -p > "$out_file"

    local w h
    w="$(sips -g pixelWidth "$out_file" | awk '/pixelWidth/{print $2}')"
    h="$(sips -g pixelHeight "$out_file" | awk '/pixelHeight/{print $2}')"
    if [ "$w" != "$expW" ] || [ "$h" != "$expH" ]; then
      status_bar_off "$serial"; "$ADB" -s "$serial" shell wm size reset
      die "Dimension mismatch for $out_file: got ${w}×${h}, expected ${expW}×${expH}."
    fi
    log "  ✓ $slot → $out_file (${w}×${h})"
  done

  status_bar_off "$serial"
  "$ADB" -s "$serial" shell wm size reset
}

main() {
  [ -x "$ADB" ] || die "adb not found at $ADB (set ANDROID_SDK_ROOT)"
  mkdir -p "$OUT_ROOT"
  if [ "${SKIP_BUILD:-0}" = "1" ]; then
    [ -f "$APK" ] || die "SKIP_BUILD=1 but no APK at $APK — run once without SKIP_BUILD."
    log "Reusing APK: $APK"
  else
    build_apk
  fi
  for dev in "${DEVICES[@]}"; do
    IFS=':' read -r avd set size <<<"$dev"
    capture_device "$avd" "$set" "$size"
  done
  log "Done. Screenshots under $OUT_ROOT/  (upload to Play Console manually)."
}

main "$@"
