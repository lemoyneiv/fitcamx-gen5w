#!/system/bin/sh
# Adaptive NEMESIS preuninstall for FITCAMX Dashcam.
# Preserves state across in-place NEMESIS updates; restores /system to stock on uninstall.
umask 022
H=${ROOT:-}/system/bin/hmgapid
ID=$(cat mod/id)
PKG=fitcamx.gen5w
STAGE=${ROOT:-}/data/local/tmp/nemesis/stage/$PKG

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

caller=$(cat /proc/$PPID/cmdline 2>/dev/null)
case $caller in
    *uninstall*) ;;
    *)
        if grep -q "^id=$PKG\$" "$STAGE/NEMESIS.data" 2>/dev/null; then
            if [ -f "$PWD/standalone/enabled" ] && [ -f "$PWD/bin/rearcam.jar" ]; then
                run_java "$PWD/bin/rearcam.jar" fitcamx.rearcam.StandaloneInstaller stop-daemon >/dev/null 2>&1 || true
            fi
            echo "update: $ID stays installed, the new package updates it"
            exit 0
        fi ;;
esac

MODE=$(cat "$PWD/mode" 2>/dev/null)
if [ "$MODE" = standalone ] || [ -d "$PWD/standalone" ] || [ -d "${ROOT:-}/data/nemesis/backup/$PKG" ]; then
    run_java "$PWD/bin/rearcam.jar" fitcamx.rearcam.StandaloneInstaller uninstall "$PWD" || {
        echo "FITCAMX standalone uninstall failed: package stays"
        exit 1
    }
fi

M=${ROOT:-}/data/hmgapi/mods/$ID
if [ -d "$M" ]; then
    if [ ! -x "$H" ]; then
        rm -rf "$M"
        echo "HMG-API isn't installed: removed $ID from /data/hmgapi/mods"
        exit 0
    fi
    "$H" ping >/dev/null 2>&1 || "$H" daemon >/dev/null 2>&1
    "$H" engine remove "$ID" --from-nemesis || {
        echo "HMG-API didn't remove $ID (see above): nothing changed, the package stays"
        exit 1
    }
    if ! "$H" engine apply; then
        echo "WARNING: $ID is removed from HMG-API, but its files aren't back to stock yet: open the HMG-API app and tap Apply changes"
        exit 0
    fi
    "$H" engine apps >/dev/null 2>&1 || true
    echo "removed $ID; restart the head unit if HMG-API says so above"
    exit 0
fi

if [ "$MODE" != standalone ]; then
    echo "removed $ID"
fi
exit 0
