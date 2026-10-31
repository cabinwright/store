#!/system/bin/sh
# cabinwrightd: the store's root service (installer, broker, tweak runner). It uses the socket
# classes of Android's framework, so app_process starts it. Dalvik writes the optimized dex to
# $ANDROID_DATA/dalvik-cache; /data/dalvik-cache belongs to system, and a NEMESIS service may
# run as root without CAP_DAC_OVERRIDE, so the cache goes in our own private directory.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
# NEMESIS starts services with umask 0. Everything here is root's own unless opened up on purpose
umask 077
HERE=${0%/*}   # no dirname on the car
BASE=/data/nemesis/cabinwright
PRIVATE=$BASE/vm
export ANDROID_DATA=$PRIVATE
export ANDROID_ROOT=${ANDROID_ROOT:-/system} ANDROID_ASSETS=/system/app
[ -n "$BOOTCLASSPATH" ] || export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar

mkdir -p "$PRIVATE/dalvik-cache"
chmod 711 "$BASE"
chmod 700 "$PRIVATE"
# Run a private copy of the jar: an update can replace the package's copy while this starts,
# and the daemon compares the two to know that the code running is the current one
# (Cabinwrightd.watchForUpdate). A new copy needs a fresh optimized dex.
rm -f "$PRIVATE"/dalvik-cache/*
cp "$HERE/cabinwrightd.jar" "$PRIVATE/cabinwrightd.jar" || exit 1
echo "$(date) starting cabinwrightd ($(grep CapEff /proc/self/status))"
# exec, so NEMESIS stops the Java process itself: it kills a service's pid, and a child of this
# shell would outlive it
export CLASSPATH=$PRIVATE/cabinwrightd.jar
exec app_process /system/bin app.cabinwright.store.daemon.Cabinwrightd
