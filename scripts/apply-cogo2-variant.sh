#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT"
APP_ID='com.itsaky.androidide.cogo2'
SOURCE_ID='com.itsaky.androidide'

# CoGo's Android terminal does not guarantee Python. Use sed only.
# Keep source namespaces unchanged; only the installed Android app identity changes.

if grep -q '^[[:space:]]*alias(libs\.plugins\.sentry)' app/build.gradle.kts; then
  sed -i 's|^[[:space:]]*alias(libs\.plugins\.sentry)|    // CoGo on-device build: Sentry CLI has no Android/ARM64 host binary\n    // alias(libs.plugins.sentry)|' app/build.gradle.kts
fi
sed -i 's|applicationId = BuildConfig\.PACKAGE_NAME|applicationId = "com.itsaky.androidide.cogo2"|' app/build.gradle.kts
if grep -q '^sentry[[:space:]]*{' app/build.gradle.kts; then
  sed -i '/^sentry[[:space:]]*{/,/^}/s|^|// |' app/build.gradle.kts
fi

sed -i 's|manifestPlaceholders\["TERMUX_PACKAGE_NAME"\] = BuildConfig\.PACKAGE_NAME|manifestPlaceholders["TERMUX_PACKAGE_NAME"] = "com.itsaky.androidide.cogo2"|' termux/termux-app/build.gradle.kts
sed -i 's|manifestPlaceholders\["TERMUX_APP_NAME"\] = "AndroidIDE"|manifestPlaceholders["TERMUX_APP_NAME"] = "Code on the Go 2"|' termux/termux-app/build.gradle.kts

TERMUX_CONSTANTS='termux/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java'
if ! grep -q 'TERMUX_CLASS_PACKAGE_NAME' "$TERMUX_CONSTANTS"; then
  sed -i 's|public static final String TERMUX_PACKAGE_NAME = "com.itsaky.androidide";|public static final String TERMUX_PACKAGE_NAME = "com.itsaky.androidide.cogo2";\n    /** Canonical Java/Kotlin source namespace. Do not use for runtime data paths or Android package identity. */\n    public static final String TERMUX_CLASS_PACKAGE_NAME = "com.itsaky.androidide";|' "$TERMUX_CONSTANTS"
fi
sed -i 's|public static final String TERMUX_APP_NAME = "Code on the Go";|public static final String TERMUX_APP_NAME = "Code on the Go 2";|' "$TERMUX_CONSTANTS"
sed -i 's|TERMUX_PACKAGE_NAME + "\.BuildConfig"|TERMUX_CLASS_PACKAGE_NAME + ".BuildConfig"|g' "$TERMUX_CONSTANTS"
sed -i 's|TERMUX_PACKAGE_NAME + "\.app\.api\.file\.FileShareReceiverActivity"|TERMUX_CLASS_PACKAGE_NAME + ".app.api.file.FileShareReceiverActivity"|g' "$TERMUX_CONSTANTS"
sed -i 's|TERMUX_PACKAGE_NAME + "\.app\.api\.file\.FileViewReceiverActivity"|TERMUX_CLASS_PACKAGE_NAME + ".app.api.file.FileViewReceiverActivity"|g' "$TERMUX_CONSTANTS"
sed -i 's|TERMUX_PACKAGE_NAME + "\.app\.TermuxActivity"|TERMUX_CLASS_PACKAGE_NAME + ".app.TermuxActivity"|g' "$TERMUX_CONSTANTS"

sed -i 's|<string name="app_name" translatable="false">Code on the Go</string>|<string name="app_name" translatable="false">Code on the Go 2</string>|' app/src/main/res/values/strings.xml

echo "CoGo 2 source identity applied: $APP_ID"
echo "Canonical source namespace preserved: $SOURCE_ID"
