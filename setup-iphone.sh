#!/bin/bash
# Prepares the NetPlanetLink iOS project for running on your own iPhone
# with a free Apple ID (Personal Team). Run on a Mac from the repo folder:
#   ./setup-iphone.sh            (auto-detects your Team ID if Xcode already has a signing certificate)
#   ./setup-iphone.sh AB12CD34EF (your Team ID)
# Full guide: docs/iPhone-install.md
set -e
cd "$(dirname "$0")"
ROOT="$(pwd)"

need() { command -v "$1" >/dev/null 2>&1; }

echo "== Checking tools"
if ! need xcodebuild || ! xcode-select -p >/dev/null 2>&1; then
  echo "Xcode is missing. Install it from the Mac App Store, open it once, then run this again."; exit 1
fi
if ! need brew; then
  echo "Homebrew is missing. Install it from https://brew.sh, then run this again."; exit 1
fi
need xcodegen || brew install xcodegen
if ! /usr/libexec/java_home -v 17 >/dev/null 2>&1 && ! /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  brew install --cask temurin@17
fi
if [ -z "$ANDROID_HOME" ] && ! grep -qs '^sdk.dir' local.properties \
   && [ ! -d "$HOME/Library/Android/sdk" ] \
   && [ ! -d /opt/homebrew/share/android-commandlinetools ] \
   && [ ! -d /usr/local/share/android-commandlinetools ]; then
  # Gradle configures the Android part of the shared project too, so it needs an SDK folder.
  brew install --cask android-commandlinetools
fi

echo "== Signing (free Apple ID / Personal Team)"
LOCAL="iosApp/Configs/Local.xcconfig"
TEAM="$1"
if [ -z "$TEAM" ] && [ -f "$LOCAL" ]; then
  TEAM="$(sed -n 's/^DEVELOPMENT_TEAM *= *\([A-Z0-9]\{10\}\).*/\1/p' "$LOCAL" | head -1)"
fi
if [ -z "$TEAM" ]; then
  # The OU field of an "Apple Development" certificate is the Team ID.
  TEAM="$(security find-certificate -a -c "Apple Development" -p 2>/dev/null \
          | openssl x509 -noout -subject 2>/dev/null \
          | sed -n 's/.*OU *= *\([A-Z0-9]\{10\}\).*/\1/p' | head -1)"
fi
if [ -n "$TEAM" ]; then
  if [ -f "$LOCAL" ] && grep -q '^DEVELOPMENT_TEAM' "$LOCAL"; then
    sed -i '' "s/^DEVELOPMENT_TEAM.*/DEVELOPMENT_TEAM = $TEAM/" "$LOCAL"
  else
    echo "DEVELOPMENT_TEAM = $TEAM" >> "$LOCAL"
  fi
  echo "Team ID: $TEAM (saved in $LOCAL)"
else
  echo "Team ID not found yet. That's fine: pick your Personal Team in Xcode"
  echo "(Signing & Capabilities), then run ./setup-iphone.sh <TEAM_ID> once so it is remembered."
fi

echo "== Generating Xcode project"
(cd iosApp && xcodegen generate)

echo "== Downloading Gradle (first time only)"
[ -n "$JAVA_HOME" ] || export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 21)"
./gradlew --version >/dev/null

echo
echo "Done. Opening iosApp/iosApp.xcodeproj - pick your iPhone at the top and press Run (Cmd+R).\nThe first build compiles the Kotlin code for the iPhone and takes several minutes."
open iosApp/iosApp.xcodeproj
