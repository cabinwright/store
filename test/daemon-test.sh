#!/bin/sh
# Host test for cabinwrightd: the real daemon on the host JVM against a mock head unit, driven over
# its socket by test/daemon_test.py. android.net's sockets are stand-ins (test/fake), which let each
# client say what uid it is playing, and `nemesis` is a script; packages, APK signatures and the
# index signature are real. What it can't show: real sockets, SO_PEERCRED, NEMESIS, the car.
#   CABINWRIGHT_APPS=<apps checkout>   default ../apps (needs the v1 branch)
#   CABINWRIGHT_MENU=<menu checkout>   default ../menu (needs the v1 branch)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$HERE/..
APPS=${CABINWRIGHT_APPS:-$ROOT/../apps}
CABINWRIGHT_MENU=${CABINWRIGHT_MENU:-$ROOT/../menu}
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
FT=$(sh "$CABINWRIGHT_MENU/api/tools/fetch-tools.sh")
BT=${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/${BUILD_TOOLS:-29.0.2}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/gen" "$OUT/classes"
pin=$(grep -v -- ----- "$APPS/tests/vectors/test-key.pub.pem" | tr -d '\n\r ')
cat >"$OUT/gen/Keys.kt" <<KEYS
package app.cabinwright.store.common

object Keys {
    const val DEBUG = true
    val RELEASE = arrayOf<Pair<String, String>>()
    val TEST = arrayOf("test" to "$pin")
}
KEYS
# .in, so the repo's lint doesn't take them for code that builds against Android
mkdir "$OUT/fake"
for f in "$HERE"/fake/android/net/*.java.in; do
    n=${f##*/}
    cp "$f" "$OUT/fake/${n%.in}"
done
"$JDK/bin/javac" -nowarn -d "$OUT/classes" "$OUT"/fake/*.java
find "$ROOT/daemon/src" "$ROOT/common/src" "$OUT/gen" -name '*.kt' >"$OUT/sources.txt"
JAVA_HOME=$JDK "$FT/kotlinc/bin/kotlinc" -jvm-target 1.8 -no-reflect -nowarn -cp "$OUT/classes" \
    -d "$OUT/classes" "@$OUT/sources.txt" 2>/dev/null
python3 -I "$HERE/daemon_test.py" "$OUT" "$JDK/bin/java" "$OUT/classes:$FT/kotlinc/lib/kotlin-stdlib.jar" \
    "$BT" "$FT/android-19.jar" "$APPS/tests/vectors/test-key.pem"
