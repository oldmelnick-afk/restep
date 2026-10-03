#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
device=$(xcrun simctl list devices available -j | python3 -c 'import sys,json; d=json.load(sys.stdin); print(next(x["udid"] for group in d["devices"].values() for x in group if x["name"].startswith("iPhone")))')
xcrun simctl boot "$device" || true
trap 'xcrun simctl shutdown "$device" || true' EXIT
xcrun simctl bootstatus "$device" -b
xcodebuild -project SoberyObratno.xcodeproj -scheme SoberyObratno -configuration Debug -sdk iphonesimulator -destination "id=$device" -derivedDataPath build-demo SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEMO_SCREENSHOTS CODE_SIGNING_ALLOWED=NO build
xcrun simctl install "$device" build-demo/Build/Products/Debug-iphonesimulator/SoberyObratno.app
container=$(xcrun simctl get_app_container "$device" com.rocketglasses.soberyobratno data)
python3 - "$container" <<'PY'
import sys,pathlib,json,shutil
root=pathlib.Path(sys.argv[1])/'Library/Application Support/SoberyObratno'
sid='11111111-1111-4111-8111-111111111111'
steps=[]
for i,note in enumerate(['Removed two screws from the back cover.','Lifted the back cover. Keep the screws in the tray.'],1):
    path=f'photos/{sid}/00000000-0000-4000-8000-{i:012d}.jpg'
    target=root/path; target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy('../design/promo/workshop-demo.png',target)
    steps.append(dict(id=f'step-{i}',number=i,photo=path,note=note,createdAt=1790982000000+i,completed=False))
(root/'library.json').write_text(json.dumps(dict(schemaVersion=1,sessions=[dict(id=sid,name='Desk clock · DEMO',createdAt=1790982000000,endedAt=1790982200000,steps=steps)])))
PY
mkdir -p demo-screenshots
xcrun simctl status_bar "$device" override --time 9:41 --batteryState charged --batteryLevel 100
for mode in session sync; do
  xcrun simctl terminate "$device" com.rocketglasses.soberyobratno || true
  xcrun simctl launch "$device" com.rocketglasses.soberyobratno "--demo-$mode"
  sleep 3
  xcrun simctl io "$device" screenshot "demo-screenshots/iphone-$mode.png"
done
