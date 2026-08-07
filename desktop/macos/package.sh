#!/usr/bin/env bash
# Builds LifeOS as a double-clickable macOS app (LifeOS.app / LifeOS.dmg).
#
# jpackage's native macOS launcher runs with no TTY when double-clicked from
# Finder — fine for a GUI app, useless for a Spring Shell CLI that needs a
# real terminal for line-editing/history. So the app's actual entry point
# (Contents/MacOS/LifeOS) is swapped for a small wrapper that opens a
# .command file — macOS's built-in "open this shell script in Terminal.app"
# file type — which in turn runs the real jpackage-built binary (renamed to
# LifeOS-bin). `open x.command` needs no special permission, unlike
# scripting Terminal via AppleScript/Apple Events (which requires a one-time
# Automation consent dialog — worse UX, and impossible to grant headlessly).
#
# Output: desktop/build/macos/LifeOS.app and LifeOS.dmg (gitignored).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
BUILD_DIR="$REPO_ROOT/desktop/build/macos"
APP_NAME="LifeOS"

echo "==> Building the backend jar"
"$BACKEND_DIR/mvnw" -q -B -f "$BACKEND_DIR/pom.xml" clean package -DskipTests

JAR_PATH="$(find "$BACKEND_DIR/target" -maxdepth 1 -name 'lifeos-backend-*.jar' ! -name '*.original' | head -1)"
if [ -z "$JAR_PATH" ]; then
  echo "Could not find the built jar under $BACKEND_DIR/target" >&2
  exit 1
fi
JAR_NAME="$(basename "$JAR_PATH")"

# jpackage's --app-version must be 1-3 dot-separated integers with a
# non-zero leading component — Maven's "0.0.1-SNAPSHOT" doesn't qualify, so
# the desktop app is versioned independently. Override with DESKTOP_APP_VERSION.
APP_VERSION="${DESKTOP_APP_VERSION:-1.0.0}"
echo "==> Packaging version ${APP_VERSION}"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# jpackage bundles everything under --input verbatim, so stage just the fat
# jar rather than pointing it at the whole Maven target/ dir (which also
# holds test-classes, generated-sources, the pre-repackage .jar.original, etc).
STAGING_DIR="$BUILD_DIR/staging"
mkdir -p "$STAGING_DIR"
cp "$JAR_PATH" "$STAGING_DIR/$JAR_NAME"

echo "==> Running jpackage"
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$STAGING_DIR" \
  --main-jar "$JAR_NAME" \
  --dest "$BUILD_DIR"

APP_DIR="$BUILD_DIR/$APP_NAME.app"
MACOS_DIR="$APP_DIR/Contents/MacOS"
REAL_BIN="$MACOS_DIR/${APP_NAME}-bin"

echo "==> Wrapping the launcher to open in Terminal"
mv "$MACOS_DIR/$APP_NAME" "$REAL_BIN"
# jpackage's native launcher derives its .cfg filename from its own argv[0]
# basename, so the cfg has to move in lockstep with the rename above.
mv "$APP_DIR/Contents/app/$APP_NAME.cfg" "$APP_DIR/Contents/app/${APP_NAME}-bin.cfg"

cat > "$MACOS_DIR/$APP_NAME.command" <<'RUNNER'
#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/LifeOS-bin"
RUNNER
chmod +x "$MACOS_DIR/$APP_NAME.command"

cat > "$MACOS_DIR/$APP_NAME" <<'LAUNCHER'
#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
open "$DIR/LifeOS.command"
LAUNCHER
chmod +x "$MACOS_DIR/$APP_NAME"

echo "==> Building the .dmg"
DMG_PATH="$BUILD_DIR/$APP_NAME.dmg"
rm -f "$DMG_PATH"
hdiutil create -volname "$APP_NAME" -srcfolder "$APP_DIR" -ov -format UDZO "$DMG_PATH" >/dev/null

echo "==> Done."
echo "    App: $APP_DIR"
echo "    Dmg: $DMG_PATH"
echo ""
echo "Unsigned build: macOS Gatekeeper will refuse to open it normally."
echo "Right-click the app (or the mounted .dmg's app) -> Open, once, to bypass."
