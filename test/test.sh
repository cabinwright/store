#!/bin/sh
# Host tests for common/ (the verifier the app and the helper share), with the host JDK.
# Uses the index repo's (cabinwright/apps) signing test vectors, so both sides check the same bytes.
#   CABINWRIGHT_APPS=<path to the apps checkout>   default ../../apps
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$HERE/..
VECTORS=${CABINWRIGHT_APPS:-$ROOT/../apps}/tests/vectors
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
# the helper too (plain Java), for its import of kia.store's record; Keys.java is generated
# by build.sh, so a stand-in here
mkdir -p "$OUT/gen/dev/cabinwright/store/common"
printf 'package dev.cabinwright.store.common;\npublic final class Keys {\n    public static final String[][] RELEASE = {}, TEST = {};\n    public static final boolean DEBUG_BUILD = false;\n}\n' \
    > "$OUT/gen/dev/cabinwright/store/common/Keys.java"
"$JDK/bin/javac" -d "$OUT" $(find "$ROOT/common/src" "$ROOT/helper/src" "$OUT/gen" "$HERE/src" -name '*.java')
"$JDK/bin/java" -cp "$OUT" dev.cabinwright.store.test.CommonTest "$VECTORS"
