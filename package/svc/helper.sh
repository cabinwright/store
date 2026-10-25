#!/system/bin/sh
# helper: installs and removes NEMESIS packages for the App Store app (dev.kiastore).
# Verifying RSA signatures needs Java, so this starts helper.jar (a dex) with dalvikvm as
# root. Dalvik writes the optimized dex to $ANDROID_DATA/dalvik-cache; /data/dalvik-cache
# belongs to system, and a NEMESIS service runs as root without CAP_DAC_OVERRIDE, so the
# cache goes in our own private directory.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
HERE=${0%/*}   # no dirname on the car
PRIVATE=/data/nemesis/kia-store
export ANDROID_DATA=$PRIVATE/vm
export ANDROID_ROOT=${ANDROID_ROOT:-/system}
[ -n "$BOOTCLASSPATH" ] || export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar

mkdir -p "$PRIVATE" "$ANDROID_DATA/dalvik-cache"
chmod 711 "$PRIVATE"
chmod 700 "$ANDROID_DATA"
# a new helper.jar (package update) needs a fresh optimized copy
rm -f "$ANDROID_DATA"/dalvik-cache/*kia.store*
echo "$(date) starting helper ($(grep CapEff /proc/self/status))"
# exec, so NEMESIS stops the Java process itself: it kills a service's pid, and a child
# of this shell would outlive it. To pick up an update the helper exits and svcmgr
# restarts it (see Helper.reloadIfUpdated).
exec dalvikvm -Xmx32m -cp "$HERE/helper.jar" dev.kiastore.helper.Helper
