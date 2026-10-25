#!/bin/sh
# Host tests for common/ (the verifier the app and the helper share), with the host JDK.
# Uses kia-apps' signing test vectors, so both sides check the same bytes.
#   KIA_APPS=<path to kia-apps>   default ../../kia-apps
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$HERE/..
VECTORS=${KIA_APPS:-$ROOT/../kia-apps}/tests/vectors
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JDK/bin/javac" -d "$OUT" $(find "$ROOT/common/src" "$HERE/src" -name '*.java')
"$JDK/bin/java" -cp "$OUT" dev.kiastore.test.CommonTest "$VECTORS"
