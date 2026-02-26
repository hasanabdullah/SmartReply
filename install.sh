#!/usr/bin/env bash
# Install SmartReply to personal profile only (user 0), skipping work profile (user 10)
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ADB="${ADB:-adb}"

if [ ! -f "$APK" ]; then
    echo "APK not found. Building first..."
    JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}" \
        "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" assembleDebug
fi

DEVICE=$($ADB devices | sed -n '2p' | cut -f1)
if [ -z "$DEVICE" ]; then
    echo "No device connected."
    exit 1
fi

echo "Installing to personal profile (user 0) on $DEVICE..."
$ADB -s "$DEVICE" install --user 0 -r "$APK"
echo "Done."
