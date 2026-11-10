#!/usr/bin/env python3
"""
FITCAMX for Gen5W — Automated Test & Binary Invariant Verification Suite
Runs:
  1. Full APK + HMG-API Mod build (optional --skip-build if already built)
  2. Host-JVM Java Unit & Mock-HTTP Integration Tests (FitcamUnitTests.java)
  3. Dalvik API 19 (KitKat) DEX bytecode & JNI symbol verification
  4. APK 4-byte zipalign, v1-only JAR signature, and 32-bit ARMv7 ELF verification
  5. AGENT_GUIDE.md strict operational invariants, Manifest, mod.json, PNG icons & shell scripts
"""
import glob
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BUILD_DIR = os.path.join(BASE_DIR, "build")
TESTS_DIR = os.path.join(BASE_DIR, "tests")

is_win = sys.platform.startswith("win")
exe_suffix = ".exe" if is_win else ""

java_home = os.environ.get("JAVA_HOME")
if not java_home or not os.path.isdir(java_home):
    for cand in [
        os.path.abspath(os.path.join(BASE_DIR, "..", "tools", "jdk-17")),
        os.path.expanduser("~/tools/jdk-17"),
        r"C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot",
    ]:
        if os.path.isdir(cand):
            java_home = cand
            break

JDK_BIN = os.path.join(java_home, "bin") if java_home else ""
JAVA = os.path.join(JDK_BIN, f"java{exe_suffix}") if os.path.isfile(os.path.join(JDK_BIN, f"java{exe_suffix}")) else (shutil.which("java") or "java")
JAVAC = os.path.join(JDK_BIN, f"javac{exe_suffix}") if os.path.isfile(os.path.join(JDK_BIN, f"javac{exe_suffix}")) else (shutil.which("javac") or "javac")

SDK_DIR = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not SDK_DIR or not os.path.isdir(SDK_DIR):
    for cand in [
        os.path.abspath(os.path.join(BASE_DIR, "..", "tools", "android-sdk")),
        os.path.expanduser("~/tools/android-sdk"),
    ]:
        if os.path.isdir(cand):
            SDK_DIR = cand
            break

ANDROID_JAR = os.path.join(SDK_DIR, "platforms", "android-19", "android.jar")
BT_DIR = os.path.join(SDK_DIR, "build-tools", "30.0.3")
DEXDUMP = os.path.join(BT_DIR, f"dexdump{exe_suffix}")
ZIPALIGN = os.path.join(BT_DIR, f"zipalign{exe_suffix}")
APKSIGNER_JAR = os.path.join(BT_DIR, "lib", "apksigner.jar")

passed = 0
failed = 0


def check(name: str, cond: bool, detail: str = ""):
    global passed, failed
    suffix = f" ({detail})" if detail else ""
    if cond:
        print(f"  [PASS] {name}{suffix}")
        passed += 1
    else:
        print(f"  [FAIL] {name}{suffix}")
        failed += 1


def run_build_if_needed(force_rebuild: bool):
    dex_path = os.path.join(BUILD_DIR, "dex", "classes.dex")
    apk_path = os.path.join(BUILD_DIR, "Fitcamx.apk")
    classes_dir = os.path.join(BUILD_DIR, "classes")
    if force_rebuild or not (os.path.isfile(dex_path) and os.path.isfile(apk_path) and os.path.isdir(classes_dir)):
        print("=== [Step 1] Building APK & HMG-API Package ===")
        subprocess.run([sys.executable, os.path.join(BASE_DIR, "tools", "build_package.py")], check=True)
    else:
        print("=== [Step 1] Using up-to-date build artifacts in build/ ===")


def run_java_unit_tests():
    print("\n=== [Step 2] Running Host-JVM Java Unit & Mock-HTTP Tests ===")
    with tempfile.TemporaryDirectory(prefix="fitcam_test_build_") as tmp:
        shim_out = os.path.join(tmp, "shim_classes")
        test_out = os.path.join(tmp, "test_classes")
        os.makedirs(shim_out, exist_ok=True)
        os.makedirs(test_out, exist_ok=True)

        shim_srcs = glob.glob(os.path.join(TESTS_DIR, "shims", "**", "*.java"), recursive=True)
        subprocess.run(
            [JAVAC, "--release", "8", "-cp", ANDROID_JAR, "-d", shim_out] + shim_srcs,
            check=True,
        )

        lib_jars = glob.glob(os.path.join(BASE_DIR, "app", "libs", "*.jar"))
        app_classes = os.path.join(BUILD_DIR, "classes")
        compile_cp = os.pathsep.join([shim_out, app_classes, ANDROID_JAR] + lib_jars)
        subprocess.run(
            [JAVAC, "--release", "8", "-cp", compile_cp, "-d", test_out, os.path.join(TESTS_DIR, "FitcamUnitTests.java")],
            check=True,
        )

        runtime_cp = os.pathsep.join([test_out, shim_out, app_classes] + lib_jars + [ANDROID_JAR])
        res = subprocess.run([JAVA, "-cp", runtime_cp, "FitcamUnitTests"])
        check("FitcamUnitTests.java suite exited cleanly", res.returncode == 0)


def verify_dex_and_jni():
    print("\n=== [Step 3] Verifying Dalvik API 19 DEX Bytecode & JNI Keep Targets ===")
    dex_file = os.path.join(BUILD_DIR, "dex", "classes.dex")
    with open(dex_file, "rb") as f:
        magic = f.read(8)
    check("DEX header uses Dalvik KitKat magic 'dex\\n035\\0'", magic == b"dex\n035\x00", repr(magic))

    with zipfile.ZipFile(ANDROID_JAR, "r") as z:
        api19_classes = {"L" + n[:-6] + ";" for n in z.namelist() if n.endswith(".class")}

    out = subprocess.check_output([DEXDUMP, "-f", dex_file], text=True)
    class_blocks = out.split("Class #")[1:]
    defined_classes = set()
    for blk in class_blocks:
        m = re.search(r"Class descriptor\s*:\s*'([^']+)'", blk)
        if m:
            defined_classes.add(m.group(1))

    super_iface_hazards = []
    for blk in class_blocks:
        m_cls = re.search(r"Class descriptor\s*:\s*'([^']+)'", blk)
        if not m_cls:
            continue
        cls = m_cls.group(1)
        m_sup = re.search(r"Superclass\s*:\s*'([^']+)'", blk)
        if m_sup:
            sup = m_sup.group(1)
            if sup not in api19_classes and sup not in defined_classes:
                super_iface_hazards.append((cls, "SUPERCLASS", sup))
        m_ifaces = re.search(r"Interfaces\s*-(.*?)Static fields\s*-", blk, re.S)
        if m_ifaces:
            for iface in re.findall(r":\s*'([^']+)'", m_ifaces.group(1)):
                if iface not in api19_classes and iface not in defined_classes:
                    super_iface_hazards.append((cls, "INTERFACE", iface))

    check(
        "Zero missing superclass/interface Dalvik verification hazards on API 19",
        len(super_iface_hazards) == 0,
        f"{len(defined_classes)} DEX classes checked, hazards={super_iface_hazards}",
    )

    disasm = subprocess.check_output([DEXDUMP, "-d", dex_file], text=True)
    current_cls = None
    fitcamx_missing = set()
    for line in disasm.splitlines():
        m_c = re.match(r"\s*Class descriptor\s*:\s*'([^']+)'", line)
        if m_c:
            current_cls = m_c.group(1)
            continue
        if current_cls and current_cls.startswith("Lfitcamx/"):
            if any(op in line for op in ("invoke-", "sget", "iget", "new-instance", "check-cast")):
                for ref in re.findall(r"(L[a-zA-Z0-9_/$]+;)", line):
                    if ref not in api19_classes and ref not in defined_classes:
                        fitcamx_missing.add((current_cls, ref))

    check("Zero missing type references inside fitcamx.app.*", len(fitcamx_missing) == 0, str(sorted(fitcamx_missing)))

    required_jni_classes = [
        "Lorg/videolan/libvlc/AWindow;",
        "Lorg/videolan/libvlc/Dialog;",
        "Lorg/videolan/libvlc/LibVLC;",
        "Lorg/videolan/libvlc/Media;",
        "Lorg/videolan/libvlc/MediaDiscoverer;",
        "Lorg/videolan/libvlc/MediaDiscoverer$Description;",
        "Lorg/videolan/libvlc/MediaPlayer;",
        "Lorg/videolan/libvlc/MediaPlayer$Chapter;",
        "Lorg/videolan/libvlc/MediaPlayer$Equalizer;",
        "Lorg/videolan/libvlc/MediaPlayer$Title;",
        "Lorg/videolan/libvlc/MediaPlayer$TrackDescription;",
        "Lorg/videolan/libvlc/RendererDiscoverer;",
        "Lorg/videolan/libvlc/RendererDiscoverer$Description;",
        "Lorg/videolan/libvlc/RendererItem;",
        "Lorg/videolan/libvlc/VLCObject;",
        "Lorg/videolan/libvlc/interfaces/IMedia$Slave;",
        "Lorg/videolan/libvlc/interfaces/IMedia$Track;",
    ]
    missing_jni = [c for c in required_jni_classes if c not in defined_classes]
    check("All libvlcjni.so & libvlc.so JNI target classes preserved in shrunk DEX", len(missing_jni) == 0, str(missing_jni))


def verify_apk_and_elf():
    print("\n=== [Step 4] Verifying Signed APK Alignment, v1 Signature & ARMv7 Native Binaries ===")
    apk_path = os.path.join(BUILD_DIR, "Fitcamx.apk")

    align_res = subprocess.run([ZIPALIGN, "-c", "4", apk_path], capture_output=True, text=True)
    check("Fitcamx.apk passes 4-byte zipalign verification", align_res.returncode == 0)

    sig_out = subprocess.check_output([JAVA, "-jar", APKSIGNER_JAR, "verify", "--verbose", apk_path], text=True)
    v1_ok = "Verified using v1 scheme (JAR signing): true" in sig_out
    v2_off = "Verified using v2 scheme (APK Signature Scheme v2): false" in sig_out
    v3_off = "Verified using v3 scheme (APK Signature Scheme v3): false" in sig_out
    check("Fitcamx.apk uses strictly v1 JAR signing (v1=true, v2=false, v3=false)", v1_ok and v2_off and v3_off)

    expected_sos = {
        "lib/armeabi-v7a/libvlc.so",
        "lib/armeabi-v7a/libvlcjni.so",
        "lib/armeabi-v7a/libc++_shared.so",
    }
    with zipfile.ZipFile(apk_path, "r") as z:
        names = set(z.namelist())
        check("APK bundles all 3 required armeabi-v7a native libraries", expected_sos.issubset(names))
        all_arm32 = True
        for so_name in expected_sos:
            with z.open(so_name) as sf:
                hdr = sf.read(20)
            # ELF magic \x7fELF, class=1 (32-bit), data=1 (LSB), e_machine=40 (EM_ARM)
            e_machine = struct.unpack("<H", hdr[18:20])[0]
            if hdr[:4] != b"\x7fELF" or hdr[4] != 1 or e_machine != 40:
                all_arm32 = False
        check("All bundled .so libraries are 32-bit little-endian ARM (EM_ARM=40)", all_arm32)


def read_png_dimensions(path: str):
    with open(path, "rb") as f:
        hdr = f.read(24)
    if len(hdr) < 24 or hdr[:8] != b"\x89PNG\r\n\x1a\n" or hdr[12:16] != b"IHDR":
        return None
    w, h = struct.unpack(">II", hdr[16:24])
    return (w, h)


def verify_operational_invariants():
    print("\n=== [Step 5] Verifying AGENT_GUIDE.md Invariants, Manifest, mod.json & Scripts ===")
    # 1. Privacy invariant: zero "Android Auto" mentions in app/src or app/res
    aa_hits = []
    set_fixed_size_hits = []
    for root_sub in ("src", "res"):
        for root, _, files in os.walk(os.path.join(BASE_DIR, "app", root_sub)):
            for fn in files:
                if fn.endswith((".java", ".xml")):
                    fp = os.path.join(root, fn)
                    with open(fp, "r", encoding="utf-8", errors="ignore") as f:
                        txt = f.read()
                    if re.search(r"\bandroid\s+auto\b", txt, re.I):
                        aa_hits.append(os.path.relpath(fp, BASE_DIR))
                    if fn.endswith(".java"):
                        code_no_comments = re.sub(r"/\*.*?\*/", "", txt, flags=re.S)
                        code_no_comments = re.sub(r"//[^\n]*", "", code_no_comments)
                        if ".setFixedSize(" in code_no_comments:
                            set_fixed_size_hits.append(os.path.relpath(fp, BASE_DIR))

    check("Zero 'Android Auto' references in app/src and app/res", len(aa_hits) == 0, str(aa_hits))
    check("Zero SurfaceHolder.setFixedSize() calls in app/src", len(set_fixed_size_hits) == 0, str(set_fixed_size_hits))

    # 2. AndroidManifest.xml checks
    manifest_path = os.path.join(BASE_DIR, "app", "AndroidManifest.xml")
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest_txt = f.read()

    forbidden_perms = ["android.permission.REBOOT", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "WAKE_LOCK"]
    found_perms = [p for p in forbidden_perms if p in manifest_txt]
    check("AndroidManifest.xml excludes unused/system permissions (REBOOT, LOCATION, WAKE_LOCK)", len(found_perms) == 0, str(found_perms))
    check(
        "AndroidManifest.xml targets SDK 19 and preserves android.max_aspect=4.0",
        'minSdkVersion="19"' in manifest_txt and 'targetSdkVersion="19"' in manifest_txt and "android.max_aspect" in manifest_txt,
    )

    # 3. mod/mod.json checks
    mod_json_path = os.path.join(BASE_DIR, "mod", "mod.json")
    with open(mod_json_path, "r", encoding="utf-8") as f:
        mod_raw = f.read()
        mod_obj = json.loads(mod_raw)

    check("mod/mod.json has NO 'media.codecs' dependency", "media.codecs" not in mod_raw and "depends" not in mod_obj)
    check("mod/mod.json declares wifi.client=true and usb=['DashcamFootage']",
          mod_obj.get("wifi", {}).get("client") is True and mod_obj.get("usb") == ["DashcamFootage"])

    # 4. Launcher icons 128x128
    tile_dim = read_png_dimensions(os.path.join(BASE_DIR, "mod", "icons", "tile.png"))
    dis_dim = read_png_dimensions(os.path.join(BASE_DIR, "mod", "icons", "tile_disabled.png"))
    with open(os.path.join(BASE_DIR, "mod", "icons", "tile.png"), "rb") as f1, open(os.path.join(BASE_DIR, "mod", "icons", "tile_disabled.png"), "rb") as f2:
        icons_distinct = f1.read() != f2.read()
    check("Launcher icons tile.png & tile_disabled.png are distinct 128x128 PNGs",
          tile_dim == (128, 128) and dis_dim == (128, 128) and icons_distinct,
          f"tile={tile_dim}, tile_disabled={dis_dim}")

    # 5. Shell script syntax checks
    sh_bin = shutil.which("sh")
    if sh_bin:
        for script_rel in ("mod/open.sh", "mod/svc/reboot.sh"):
            script_p = os.path.join(BASE_DIR, script_rel)
            if os.path.isfile(script_p):
                r = subprocess.run([sh_bin, "-n", script_p], capture_output=True, text=True)
                check(f"POSIX shell syntax valid: {script_rel}", r.returncode == 0, r.stderr.strip())


def main():
    force_rebuild = "--rebuild" in sys.argv
    run_build_if_needed(force_rebuild)
    run_java_unit_tests()
    verify_dex_and_jni()
    verify_apk_and_elf()
    verify_operational_invariants()

    print(f"\n============================================================")
    print(f"FINAL VERIFICATION SUMMARY: {passed} passed, {failed} failed")
    print(f"============================================================")
    sys.exit(1 if failed > 0 else 0)


if __name__ == "__main__":
    main()
