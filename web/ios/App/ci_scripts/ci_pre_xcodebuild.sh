#!/bin/sh

# Xcode Cloud pre-xcodebuild step: version stamping + archive-time release guard.
#
# Build numbering (capacitor-ios-spec §H.8): the app now ships on the After
# Duty record (6809038854, com.afterduty.app). Its Xcode Cloud workflow's
# CI_BUILD_NUMBER restarts from 1; the +100 offset is kept so CFBundleVersion
# stays strictly above every build ever uploaded on the retired VA Claim Path
# record (last: 168) — the same binary lineage, no collisions if a build is ever
# re-pointed. MARKETING_VERSION is pinned here (also set statically in
# project.pbxproj so local builds agree) and matches the Android versionName.
#
# Info.plist reads $(MARKETING_VERSION)/$(CURRENT_PROJECT_VERSION) from build
# settings, so rewriting project.pbxproj is sufficient. sed is used instead of
# agvtool because the Capacitor template does not set VERSIONING_SYSTEM =
# apple-generic.

set -e

MARKETING_VERSION="2.0.1"
BUILD_OFFSET=100

REPO="${CI_PRIMARY_REPOSITORY_PATH:-$(cd "$(dirname "$0")/../../../.." && pwd)}"
WEB="$REPO/web"
PBXPROJ="$WEB/ios/App/App.xcodeproj/project.pbxproj"

if [ -z "$CI_BUILD_NUMBER" ]; then
  echo "✗ CI_BUILD_NUMBER is unset — this script is meant to run on Xcode Cloud."
  exit 1
fi

BUILD_NUMBER=$((CI_BUILD_NUMBER + BUILD_OFFSET))
echo "→ Stamping CURRENT_PROJECT_VERSION=${BUILD_NUMBER} (CI_BUILD_NUMBER=${CI_BUILD_NUMBER} + ${BUILD_OFFSET}), MARKETING_VERSION=${MARKETING_VERSION}"
sed -i '' -E "s/CURRENT_PROJECT_VERSION = [0-9]+;/CURRENT_PROJECT_VERSION = ${BUILD_NUMBER};/g" "$PBXPROJ"
sed -i '' -E "s/MARKETING_VERSION = [^;]*;/MARKETING_VERSION = ${MARKETING_VERSION};/g" "$PBXPROJ"
grep -E "CURRENT_PROJECT_VERSION|MARKETING_VERSION" "$PBXPROJ" | sort -u | sed 's/^[[:space:]]*/  /'

# Archive-time re-assert of the §F.2 release guard (ci_post_clone already
# checked once; this catches anything that mutated the workspace in between).
if [ "$CI_XCODEBUILD_ACTION" = "archive" ]; then
  echo "→ Archive action: re-asserting the release guard"
  for LEAK in "$WEB/out/dev" "$WEB/ios/App/App/public/dev"; do
    if [ -e "$LEAK" ]; then
      echo "✗ RELEASE GUARD FAILED: $LEAK exists — /dev fixture routes must never ship in an archive."
      exit 1
    fi
  done
  if [ ! -f "$WEB/ios/App/App/GoogleService-Info.plist" ]; then
    echo "✗ web/ios/App/App/GoogleService-Info.plist is missing at archive time."
    echo "  ci_post_clone restores it from the GOOGLE_SERVICE_INFO_PLIST_BASE64 secret."
    exit 1
  fi
  echo "  clean — archive may proceed"
fi

echo "✓ ci_pre_xcodebuild complete"
