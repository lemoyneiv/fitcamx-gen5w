#!/system/bin/sh
# Open the FITCAMX app from NEMESIS Manager (Launch on the FITCAMX package),
# installing its APK first if needed.
MOD=${ROOT:-}/data/hmgapi/mods/fitcamx-gen5w
say() { echo "FITCAMX: $1"; }
[ "$(getprop sys.boot_completed)" = 1 ] || { say "Android isn't up yet: try again in a minute"; exit 1; }
case "$(pm path fitcamx.app 2>/dev/null)" in
    package:*) ;;
    *)
        A=$MOD/app/Fitcamx.apk
        [ -f "$A" ] || { say "the app isn't on this unit: install FITCAMX again"; exit 1; }
        setprop sys.boot_completed 0
        r=$(pm install -r "$A" 2>&1)
        setprop sys.boot_completed 1
        case "$r" in
            *Success*) say "installed the app" ;;
            *) say "the app didn't install: $r"; exit 1 ;;
        esac ;;
esac
am start -n fitcamx.app/.MainActivity >/dev/null 2>&1 || { say "couldn't open the app"; exit 1; }
