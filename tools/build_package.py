#!/usr/bin/env python3
import argparse
import glob
import os
import shutil
import subprocess
import sys
import zipfile

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(BASE_DIR, "tools"))

import cloud_env
import sign


def main() -> None:
    parser = argparse.ArgumentParser(description="Build, shrink, align, and sign FITCAMX Gen5W packages.")
    parser.add_argument(
        "--require-release-keys",
        action="store_true",
        default=os.environ.get("FITCAMX_REQUIRE_RELEASE_KEYS") == "1",
        help="Fail if production signing keys are not configured (recommended for tagged releases)",
    )
    parser.add_argument(
        "--allow-custom-keys",
        action="store_true",
        help="With --require-release-keys, allow non-ephemeral keys whose identity differs from v0.7.0",
    )
    parser.add_argument(
        "--sync-gdrive",
        action="store_true",
        help="Upload built packages in dist/ to Google Drive after packaging",
    )
    parser.add_argument(
        "--usb-only",
        action="store_true",
        help="With --sync-gdrive, upload only *-usb.zip flash-drive packages",
    )
    parser.add_argument(
        "--allow-dev-keys",
        action="store_true",
        help="With --sync-gdrive, allow uploading dev-signed builds into dev-builds/ subfolder",
    )
    args = parser.parse_args()

    is_win = sys.platform.startswith("win")
    exe_suffix = ".exe" if is_win else ""

    jdk = cloud_env.resolve_jdk()
    JAVA = jdk.java
    JAVAC = jdk.javac
    KEYTOOL = jdk.keytool

    SDK_DIR = cloud_env.ensure_android_sdk()
    ANDROID_JAR = os.path.join(SDK_DIR, "platforms", "android-19", "android.jar")
    BT_DIR = os.path.join(SDK_DIR, "build-tools", "30.0.3")

    AAPT = os.path.join(BT_DIR, f"aapt{exe_suffix}")
    ZIPALIGN = os.path.join(BT_DIR, f"zipalign{exe_suffix}")
    D8_JAR = os.path.join(BT_DIR, "lib", "d8.jar")
    APKSIGNER_JAR = os.path.join(BT_DIR, "lib", "apksigner.jar")

    hmgapi_dir = cloud_env.ensure_hmgapi()
    keys = cloud_env.resolve_signing_keys(KEYTOOL, require_release=args.require_release_keys)
    if args.require_release_keys and not args.allow_custom_keys and not keys.matches_release_identity:
        sys.exit(
            f"ERROR: --require-release-keys expected v0.7.0 identity "
            f"(UUID={cloud_env.EXPECTED_RELEASE_UUID}, APK SHA256={cloud_env.EXPECTED_APK_CERT_SHA256}),\n"
            f"  but active keys produced UUID={keys.author_uuid}, APK SHA256={keys.apk_cert_sha256}.\n"
            f"  Pass --allow-custom-keys if you intentionally rotated signing keys."
        )

    BUILD_DIR = os.path.join(BASE_DIR, "build")
    DIST_DIR = os.path.join(BASE_DIR, "dist")

    path_parts = [BT_DIR, os.environ.get("PATH", "")]
    if jdk.java_home and os.path.isdir(jdk.java_home):
        os.environ["JAVA_HOME"] = jdk.java_home
        path_parts.insert(0, os.path.join(jdk.java_home, "bin"))
    os.environ["PATH"] = os.pathsep.join(p for p in path_parts if p)

    print("=== FITCAMX Gen5W Build System ===")
    print("Base directory:", BASE_DIR)
    print("JAVA_HOME:     ", jdk.java_home or "(system PATH)")
    print("Android JAR:   ", ANDROID_JAR)
    print("Build Tools:   ", BT_DIR)
    print("HMG-API Dir:   ", hmgapi_dir)
    print("Signing Keys:  ", keys.source)

    shutil.rmtree(BUILD_DIR, ignore_errors=True)
    os.makedirs(os.path.join(BUILD_DIR, "gen"), exist_ok=True)
    os.makedirs(os.path.join(BUILD_DIR, "classes"), exist_ok=True)
    os.makedirs(os.path.join(BUILD_DIR, "dex"), exist_ok=True)
    os.makedirs(DIST_DIR, exist_ok=True)

    # 1. Generate R.java with aapt
    print("\n[1/6] Generating R.java via aapt...")
    cmd_gen_r = [
        AAPT, "package", "-m",
        "-J", os.path.join(BUILD_DIR, "gen"),
        "-M", os.path.join(BASE_DIR, "app", "AndroidManifest.xml"),
        "-S", os.path.join(BASE_DIR, "app", "res"),
        "-I", ANDROID_JAR,
    ]
    subprocess.run(cmd_gen_r, check=True)
    r_java_files = glob.glob(os.path.join(BUILD_DIR, "gen", "**", "R.java"), recursive=True)
    print("  Generated R.java:", r_java_files)

    # 2. Compile Java Sources
    print("\n[2/6] Compiling Java sources with javac (target: Java 8)...")
    java_srcs = glob.glob(os.path.join(BASE_DIR, "app", "src", "**", "*.java"), recursive=True) + r_java_files
    lib_jars = glob.glob(os.path.join(BASE_DIR, "app", "libs", "*.jar"))
    classpath = ANDROID_JAR
    if lib_jars:
        classpath = classpath + os.pathsep + os.pathsep.join(lib_jars)
        print("  Classpath libraries:", lib_jars)

    cmd_javac = [
        JAVAC, "--release", "8", "-nowarn", "-Xlint:-options",
        "-cp", classpath,
        "-d", os.path.join(BUILD_DIR, "classes"),
    ] + java_srcs
    subprocess.run(cmd_javac, check=True)
    print("  Compiled", len(java_srcs), "Java source files.")

    # 3. Shrink, optimize & convert class files to Dalvik dex (min-api 19) via R8
    print("\n[3/6] Shrinking & converting bytecode to Dalvik DEX via R8 (min-api 19)...")
    class_files = []
    for root, _, files in os.walk(os.path.join(BUILD_DIR, "classes")):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))

    proguard_rules = os.path.join(BASE_DIR, "tools", "proguard-rules.pro")
    cmd_r8 = [
        JAVA, "-cp", D8_JAR, "com.android.tools.r8.R8",
        "--release", "--min-api", "19",
        "--lib", ANDROID_JAR,
        "--pg-conf", proguard_rules,
        "--output", os.path.join(BUILD_DIR, "dex"),
    ] + lib_jars + class_files
    subprocess.run(cmd_r8, check=True)
    dex_file = os.path.join(BUILD_DIR, "dex", "classes.dex")
    print("  Generated classes.dex (", os.path.getsize(dex_file), "bytes)")

    # 4. Package resources with aapt
    print("\n[4/6] Packaging APK resources via aapt...")
    unsigned_apk = os.path.join(BUILD_DIR, "unsigned.apk")
    cmd_aapt = [
        AAPT, "package", "-f",
        "-M", os.path.join(BASE_DIR, "app", "AndroidManifest.xml"),
        "-S", os.path.join(BASE_DIR, "app", "res"),
        "-I", ANDROID_JAR,
        "-F", unsigned_apk,
    ]
    subprocess.run(cmd_aapt, check=True)

    # Inject classes.dex and native .so libraries into unsigned APK using DEFLATE compression.
    # On Android 4.4.2 KitKat (API 19), PackageManager extracts lib/armeabi-v7a/*.so into /data/app-lib/
    # while retaining a copy of Fitcamx.apk in /data/app/, so ZIP_DEFLATED saves ~37.7 MB of /data flash.
    with zipfile.ZipFile(unsigned_apk, "a", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.write(dex_file, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
        jni_dir = os.path.join(BASE_DIR, "app", "src", "main", "jniLibs")
        if os.path.isdir(jni_dir):
            for root, _, files in os.walk(jni_dir):
                for f in sorted(files):
                    if f.endswith(".so"):
                        full_p = os.path.join(root, f)
                        rel_p = os.path.relpath(full_p, jni_dir).replace("\\", "/")
                        arcname = "lib/" + rel_p
                        z.write(full_p, arcname, compress_type=zipfile.ZIP_DEFLATED)
                        print(f"  Injected native lib (deflated): {arcname} ({os.path.getsize(full_p):,} bytes)")
    print("  Injected classes.dex and native libraries into APK.")

    # 5. Zipalign & Sign APK
    print("\n[5/6] Zipaligning & signing APK...")
    aligned_apk = os.path.join(BUILD_DIR, "aligned.apk")
    cmd_align = [ZIPALIGN, "-f", "4", unsigned_apk, aligned_apk]
    subprocess.run(cmd_align, check=True)

    final_apk = os.path.join(BUILD_DIR, "Fitcamx.apk")
    cmd_sign = [
        JAVA, "-jar", APKSIGNER_JAR, "sign",
        "--ks", keys.keystore_path,
        "--ks-pass", f"pass:{keys.keystore_pass}",
        "--ks-key-alias", keys.key_alias,
        "--key-pass", f"pass:{keys.key_pass}",
        "--min-sdk-version", "19",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "false",
        "--v3-signing-enabled", "false",
        "--out", final_apk,
        aligned_apk,
    ]
    subprocess.run(cmd_sign, check=True)
    print("  Successfully signed Fitcamx.apk (", os.path.getsize(final_apk), "bytes)")

    # 6. Package HMG-API Mod & NEMESIS Packages
    print("\n[6/6] Packaging and signing HMG-API Mod & NEMESIS USB archives...")
    mod_stage = os.path.join(BUILD_DIR, "mod")
    os.makedirs(os.path.join(mod_stage, "app"), exist_ok=True)
    os.makedirs(os.path.join(mod_stage, "icons"), exist_ok=True)

    shutil.copy2(os.path.join(BASE_DIR, "mod", "mod.json"), mod_stage)
    if os.path.isfile(os.path.join(BASE_DIR, "mod", "open.sh")):
        shutil.copy2(os.path.join(BASE_DIR, "mod", "open.sh"), mod_stage)
    features_dir = os.path.join(BASE_DIR, "mod", ".features")
    if os.path.isdir(features_dir):
        shutil.copytree(features_dir, os.path.join(mod_stage, ".features"), dirs_exist_ok=True)
    svc_dir = os.path.join(BASE_DIR, "mod", "svc")
    if os.path.isdir(svc_dir):
        shutil.copytree(svc_dir, os.path.join(mod_stage, "svc"), dirs_exist_ok=True)
    shutil.copy2(final_apk, os.path.join(mod_stage, "app", "Fitcamx.apk"))
    for icon in glob.glob(os.path.join(BASE_DIR, "mod", "icons", "*.png")):
        shutil.copy2(icon, os.path.join(mod_stage, "icons"))

    # Clean stale packages from dist/ before generating current release archives
    for old_pkg in glob.glob(os.path.join(DIST_DIR, "fitcamx-gen5w-v*.zip")):
        os.remove(old_pkg)

    sign.build(mod_stage, DIST_DIR, keys.author_key_path, hmgapi_dir)

    print("\n================ BUILD SUCCESSFUL! ================")
    if keys.matches_release_identity:
        print(f"[SIGNING] RELEASE IDENTITY VERIFIED (UUID: {keys.author_uuid})")
    elif keys.is_ephemeral:
        print(
            f"[SIGNING] EPHEMERAL DEV KEYS USED (UUID: {keys.author_uuid}) — "
            "for testing/CI; import release keys (`python3 tools/manage_keys.py import`) for head-unit upgrades."
        )
    else:
        print(f"[SIGNING] CUSTOM KEYS USED (UUID: {keys.author_uuid})")

    print("Generated packages in:", DIST_DIR)
    for f in sorted(os.listdir(DIST_DIR)):
        p = os.path.join(DIST_DIR, f)
        if os.path.isfile(p):
            print(f"  {f} ({os.path.getsize(p):,} bytes)")

    if args.sync_gdrive:
        import sync_gdrive

        print()
        sync_gdrive.sync_packages(
            src_dir=DIST_DIR,
            usb_only=args.usb_only,
            allow_dev_keys=args.allow_dev_keys,
        )


if __name__ == "__main__":
    main()
