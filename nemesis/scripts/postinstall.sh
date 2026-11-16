#!/system/bin/sh
# Adaptive NEMESIS postinstall for FITCAMX Dashcam (cwd: /data/nemesis/apps/fitcamx.gen5w).
# Automatically detects whether HMG-API is installed on the head unit:
#   - HMG-API present: installs & applies mod/package.zip through HMG-API's engine.
#   - HMG-API absent:  installs standalone on NEMESIS via StandaloneInstaller (enables
#                      Gen5W Wi-Fi client with stock backups, installs Fitcamx.apk, and
#                      starts the @fitcamx.root on-demand service broker).
umask 022
H=${ROOT:-}/system/bin/hmgapid
ID=$(cat mod/id)

say() { echo "FITCAMX ($ID): $1"; }
toast() {
    [ -z "$ROOT" ] || return 0
    [ "$(getprop sys.boot_completed)" = 1 ] || return 0
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}

run_java() {
    _jar=$1
    shift
    if [ -n "$FITCAMX_HOST_CLASSPATH" ]; then
        "${FITCAMX_HOST_JAVA:-java}" -cp "$FITCAMX_HOST_CLASSPATH" "$@"
        return $?
    fi
    if [ -z "$BOOTCLASSPATH" ] && [ -f /init.environ.rc ]; then
        while read _k _n _v; do
            if [ "$_k" = "export" ] && [ "$_n" = "BOOTCLASSPATH" ] && [ -n "$_v" ]; then
                export BOOTCLASSPATH="$_v"
                break
            fi
        done < /init.environ.rc
    fi
    CLASSPATH="$_jar" ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/app_process /system/bin "$@"
}

if [ -x "$H" ] && [ -f "${ROOT:-}/data/hmgapi/uninstall.sh" ]; then
    say "HMG-API detected — installing via HMG-API engine"
    # If migrating from an earlier standalone install (or sideloaded APK), clean up standalone
    # /system backups and let HMG-API adopt fitcamx.app while keeping /data/data/fitcamx.app.
    if [ -d "${ROOT:-}/data/nemesis/backup/fitcamx.gen5w" ] || [ ! -d "${ROOT:-}/data/hmgapi/mods/$ID" ]; then
        run_java "$PWD/bin/rearcam.jar" fitcamx.rearcam.StandaloneInstaller migrate-to-hmgapi "$PWD" >/dev/null 2>&1 || true
    fi
    rm -rf "$PWD/standalone"

    "$H" ping >/dev/null 2>&1 || "$H" daemon >/dev/null 2>&1
    any=
    for d in ${HMGAPI_USB_DIRS:-/storage/usb0 /storage/usb1 /storage/usb2}; do
        [ -f "$d/HMGAPI/ANY-CAR" ] && any=--any-car
    done
    new=yes
    [ -d "${ROOT:-}/data/hmgapi/mods/$ID" ] && new=no
    if ! "$H" engine install "$PWD/mod/package.zip" $any --from-nemesis; then
        say "HMG-API didn't install it (see above): nothing changed. Uninstall this package in NEMESIS Manager."
        toast "HMG-API didn't install FITCAMX: see NEMESIS log"
        exit 1
    fi
    if ! r=$("$H" engine apply 2>&1); then
        echo "$r"
        if [ $new = yes ]; then
            "$H" engine remove "$ID" --from-nemesis >/dev/null 2>&1
            say "HMG-API couldn't apply it (see above), so it's not installed: nothing changed. Uninstall this package in NEMESIS Manager."
        else
            say "the update is in HMG-API, but it couldn't apply it (see above): open the HMG-API app to review and Apply changes."
        fi
        toast "HMG-API couldn't apply FITCAMX: see HMG-API app"
        exit 1
    fi
    echo "$r"
    "$H" engine apps >/dev/null 2>&1 || true
    echo hmgapi > "$PWD/mode"
    case $r in
        *"restart the head unit"*)
            say "installed via HMG-API: restart the head unit to use it"
            toast "FITCAMX installed: restart the head unit" ;;
        *)
            say "installed via HMG-API"
            toast "FITCAMX installed" ;;
    esac
    exit 0
fi

say "HMG-API not installed — installing in standalone NEMESIS mode"
if ! r=$(run_java "$PWD/bin/rearcam.jar" fitcamx.rearcam.StandaloneInstaller install "$PWD" "$PWD/mod/package.zip" 2>&1); then
    echo "$r"
    say "standalone install failed (see above)"
    toast "FITCAMX standalone install failed: see NEMESIS log"
    exit 1
fi
echo "$r"
echo standalone > "$PWD/mode"
if [ -z "$ROOT" ] && [ -x "$PWD/bin/service.sh" ]; then
    "$PWD/bin/service.sh" </dev/null >/dev/null 2>&1 &
fi
case $r in
    *"restart the head unit"*)
        say "standalone install complete: restart the head unit to finish enabling Wi-Fi"
        toast "FITCAMX installed: restart the head unit" ;;
    *)
        say "standalone install complete"
        toast "FITCAMX installed" ;;
esac
exit 0
