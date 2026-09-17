#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT"
APP_ID='com.itsaky.androidide.cogo2'
SOURCE_ID='com.itsaky.androidide'

# Keep source namespaces unchanged; only the installed Android app identity changes.
python3 - <<'PY'
from pathlib import Path

p = Path('app/build.gradle.kts')
s = p.read_text()
s = s.replace('alias(libs.plugins.sentry)', '// CoGo on-device build: Sentry CLI has no Android/ARM64 host binary\n    // alias(libs.plugins.sentry)', 1)
s = s.replace('applicationId = BuildConfig.PACKAGE_NAME', 'applicationId = "com.itsaky.androidide.cogo2"', 1)
s = s.replace('sentry {\n    includeProguardMapping = false\n}', '// CoGo on-device build: runtime Sentry SDK remains; build-time CLI integration is disabled.\n// sentry {\n//     includeProguardMapping = false\n// }', 1)
p.write_text(s)

p = Path('termux/termux-app/build.gradle.kts')
s = p.read_text()
s = s.replace('manifestPlaceholders["TERMUX_PACKAGE_NAME"] = BuildConfig.PACKAGE_NAME', 'manifestPlaceholders["TERMUX_PACKAGE_NAME"] = "com.itsaky.androidide.cogo2"', 1)
s = s.replace('manifestPlaceholders["TERMUX_APP_NAME"] = "AndroidIDE"', 'manifestPlaceholders["TERMUX_APP_NAME"] = "Code on the Go 2"', 1)
p.write_text(s)

p = Path('termux/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java')
s = p.read_text()
s = s.replace('public static final String TERMUX_APP_NAME = "Code on the Go";', 'public static final String TERMUX_APP_NAME = "Code on the Go 2";', 1)
s = s.replace('public static final String TERMUX_PACKAGE_NAME = "com.itsaky.androidide";', 'public static final String TERMUX_PACKAGE_NAME = "com.itsaky.androidide.cogo2";\n    /** Canonical Java/Kotlin source namespace. Do not use for runtime data paths or Android package identity. */\n    public static final String TERMUX_CLASS_PACKAGE_NAME = "com.itsaky.androidide";', 1)
# These values name compiled classes, not the installed application package. Preserve canonical source namespace.
for suffix in [
    '.BuildConfig',
    '.app.api.file.FileShareReceiverActivity',
    '.app.api.file.FileViewReceiverActivity',
    '.app.TermuxActivity',
]:
    s = s.replace('TERMUX_PACKAGE_NAME + "' + suffix + '"', 'TERMUX_CLASS_PACKAGE_NAME + "' + suffix + '"')
p.write_text(s)

p = Path('app/src/main/res/values/strings.xml')
s = p.read_text().replace('<string name="app_name" translatable="false">Code on the Go</string>', '<string name="app_name" translatable="false">Code on the Go 2</string>', 1)
p.write_text(s)
PY

echo "CoGo 2 source identity applied: $APP_ID"
echo "Canonical source namespace preserved: $SOURCE_ID"
