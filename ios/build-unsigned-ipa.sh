#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p release
extra=(SWIFT_ACTIVE_COMPILATION_CONDITIONS=)
output=ReStep-unsigned.ipa
if [[ "${RESTEP_AUTO_WIFI:-0}" == "1" ]]; then
  extra=(SWIFT_ACTIVE_COMPILATION_CONDITIONS=AUTO_WIFI)
  output=ReStep-AutoWiFi.ipa
fi
xcodebuild -project SoberyObratno.xcodeproj -scheme SoberyObratno \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO "${extra[@]}" build
rm -rf release/Payload
mkdir -p release/Payload
cp -R build/Build/Products/Release-iphoneos/SoberyObratno.app release/Payload/
if [[ "${RESTEP_AUTO_WIFI:-0}" == "1" ]]; then
  # Preserve the requested capability for re-signing with a paid developer profile.
  codesign --force --sign - --entitlements SoberyObratno/AutoWiFi.entitlements release/Payload/SoberyObratno.app
fi
( cd release && /usr/bin/zip -qry "$output" Payload )
rm -rf release/Payload
echo "release/$output"
