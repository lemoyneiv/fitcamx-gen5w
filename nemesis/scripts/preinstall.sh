#!/system/bin/sh
# NEMESIS preinstall for FITCAMX Dashcam (adaptive: HMG-API or Standalone NEMESIS).
# Runs in the staged package folder before NEMESIS copies or registers anything.
[ -f mod/id ] && [ -f mod/package.zip ] && [ -f bin/rearcam.jar ] || {
    echo "FITCAMX: incomplete package (missing mod/package.zip or bin/rearcam.jar)"
    exit 1
}
exit 0
