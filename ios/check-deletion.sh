#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
device=$(xcrun simctl list devices available -j | python3 -c 'import sys,json; d=json.load(sys.stdin); print(next(x["udid"] for group in d["devices"].values() for x in group if x["name"].startswith("iPhone")))')
xcrun simctl boot "$device" || true
trap 'xcrun simctl shutdown "$device" || true' EXIT
xcrun simctl bootstatus "$device" -b
xcodebuild -project SoberyObratno.xcodeproj -scheme SoberyObratno \
  -configuration Debug -sdk iphonesimulator -destination "id=$device" \
  -derivedDataPath build-checks SWIFT_ACTIVE_COMPILATION_CONDITIONS=DELETION_CHECKS \
  CODE_SIGNING_ALLOWED=NO build
xcrun simctl install "$device" build-checks/Build/Products/Debug-iphonesimulator/SoberyObratno.app
xcrun simctl launch --console "$device" com.rocketglasses.soberyobratno | tee deletion-checks.log
grep -q DELETION_CHECKS_PASSED deletion-checks.log
