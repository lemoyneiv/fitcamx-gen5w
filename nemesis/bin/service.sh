#!/system/bin/sh
# NEMESIS background service for FITCAMX standalone mode (@fitcamx.root).
# Exits immediately with zero overhead when HMG-API manages FITCAMX.
DIR=${0%/*}/..
[ -f "$DIR/standalone/enabled" ] || exit 0
if [ -x "${ROOT:-}/system/bin/hmgapid" ] && [ -d "${ROOT:-}/data/hmgapi/mods/fitcamx-gen5w" ]; then
    exit 0
fi
JAR=$DIR/bin/rearcam.jar
[ -f "$JAR" ] || exit 0

if [ -z "$BOOTCLASSPATH" ] && [ -f /init.environ.rc ]; then
    while read _k _n _v; do
        if [ "$_k" = "export" ] && [ "$_n" = "BOOTCLASSPATH" ] && [ -n "$_v" ]; then
            export BOOTCLASSPATH="$_v"
            break
        fi
    done < /init.environ.rc
fi

export CLASSPATH="$JAR"
export ANDROID_ROOT=/system
export ANDROID_DATA=/data

if [ -f "$DIR/standalone/apk.pending" ]; then
    (
        while [ "$(getprop sys.boot_completed)" != 1 ]; do
            sleep 2
        done
        /system/bin/app_process /system/bin fitcamx.rearcam.StandaloneInstaller ensure-app "$DIR" >/dev/null 2>&1
    ) </dev/null >/dev/null 2>&1 &
fi

exec /system/bin/app_process /system/bin --nice-name=fitcamx.root \
    fitcamx.rearcam.StandaloneRootDaemon "$DIR" </dev/null >/dev/null 2>&1
