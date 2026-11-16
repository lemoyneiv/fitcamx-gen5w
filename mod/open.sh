#!/system/bin/sh
# Open the FITCAMX app from NEMESIS Manager (Launch on the FITCAMX package),
# working seamlessly in both HMG-API mode and Standalone NEMESIS mode.
PKG=${0%/*}/..
MOD=${ROOT:-}/data/hmgapi/mods/fitcamx-gen5w
say() { echo "FITCAMX: $1"; }

[ "$(getprop sys.boot_completed)" = 1 ] || { say "Android isn't up yet: try again in a minute"; exit 1; }

case "$(pm path fitcamx.app 2>/dev/null)" in
    package:*) ;;
    *)
        if [ -f "$MOD/app/Fitcamx.apk" ]; then
            TMP=${ROOT:-}/data/local/tmp/fitcamx-open.apk
            cp "$MOD/app/Fitcamx.apk" "$TMP"
            chmod 644 "$TMP"
            setprop sys.boot_completed 0
            r=$(pm install -r "$TMP" 2>&1)
            setprop sys.boot_completed 1
            rm -f "$TMP"
            case "$r" in
                *Success*) say "installed the app" ;;
                *) say "the app didn't install: $r"; exit 1 ;;
            esac
        elif [ -f "$PKG/bin/rearcam.jar" ] && [ -f "$PKG/mod/package.zip" ]; then
            if [ -z "$BOOTCLASSPATH" ] && [ -f /init.environ.rc ]; then
                while read _k _n _v; do
                    if [ "$_k" = "export" ] && [ "$_n" = "BOOTCLASSPATH" ] && [ -n "$_v" ]; then
                        export BOOTCLASSPATH="$_v"
                        break
                    fi
                done < /init.environ.rc
            fi
            CLASSPATH="$PKG/bin/rearcam.jar" ANDROID_ROOT=/system ANDROID_DATA=/data \
                /system/bin/app_process /system/bin fitcamx.rearcam.StandaloneInstaller ensure-app "$PKG" || {
                say "the app didn't install"
                exit 1
            }
            say "installed the app"
        else
            say "the app isn't on this unit: install FITCAMX again"
            exit 1
        fi ;;
esac

if [ -z "$ROOT" ] && [ -f "$PKG/standalone/enabled" ] && [ -x "$PKG/bin/service.sh" ]; then
    "$PKG/bin/service.sh" </dev/null >/dev/null 2>&1 &
fi

am start -n fitcamx.app/.MainActivity >/dev/null 2>&1 || { say "couldn't open the app"; exit 1; }
