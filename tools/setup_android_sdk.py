#!/usr/bin/env python3
"""Cross-platform Android SDK setup helper (API 19 platform + Build-Tools 30.0.3).

Supports persistent archive caching (e.g. ~/.gemini/fitcamx-gen5w/cache in Antigravity
Cloud VMs or FITCAMX_CACHE_DIR in CI) so ephemeral cloud environments unpack locally
in <1s without re-downloading from dl.google.com.
"""
import os
import sys
import tempfile
import urllib.request
import zipfile

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def _default_sdk_dir() -> str:
    env_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if env_sdk:
        return os.path.abspath(env_sdk)
    sibling = os.path.abspath(os.path.join(BASE_DIR, "..", "tools", "android-sdk"))
    if os.path.isdir(sibling):
        return sibling
    return os.path.expanduser("~/tools/android-sdk")


def _default_cache_dir() -> str:
    if os.environ.get("FITCAMX_CACHE_DIR"):
        return os.path.abspath(os.environ["FITCAMX_CACHE_DIR"])
    gemini_dir = os.path.expanduser("~/.gemini")
    if os.path.isdir(gemini_dir):
        return os.path.join(gemini_dir, "fitcamx-gen5w", "cache")
    return os.path.expanduser("~/.cache/fitcamx-gen5w")


def _copy_bytes(src: str, dst: str) -> None:
    """Copy file bytes without calling chmod/utime (safe on FUSE/shared mounts)."""
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(src, "rb") as rf, open(dst, "wb") as wf:
        while True:
            chunk = rf.read(1024 * 1024)
            if not chunk:
                break
            wf.write(chunk)


def _fetch_or_cached_zip(url: str, cache_name: str, cache_dir: str) -> tuple[str, bool]:
    """Return (zip_path, is_temp). Uses persistent cache when available."""
    cached_path = os.path.join(cache_dir, cache_name) if cache_dir else ""
    if cached_path and os.path.isfile(cached_path) and zipfile.is_zipfile(cached_path):
        print(f"  Using cached archive: {cached_path}")
        return cached_path, False

    fd, tmp_zip = tempfile.mkstemp(prefix="fitcamx_sdk_", suffix=".zip")
    os.close(fd)
    print(f"  Downloading {url} ...")
    urllib.request.urlretrieve(url, tmp_zip)
    if cached_path:
        try:
            _copy_bytes(tmp_zip, cached_path)
            print(f"  Cached archive to: {cached_path}")
        except OSError as exc:
            print(f"  Note: could not persist archive cache ({exc}); continuing.")
    return tmp_zip, True


def ensure_sdk(sdk_dir: str | None = None, cache_dir: str | None = None) -> str:
    """Ensure Android API 19 android.jar and Build-Tools 30.0.3 exist in sdk_dir."""
    sdk_dir = os.path.abspath(sdk_dir or _default_sdk_dir())
    cache_dir = cache_dir if cache_dir is not None else _default_cache_dir()
    os.makedirs(sdk_dir, exist_ok=True)

    is_win = sys.platform.startswith("win")
    is_mac = sys.platform == "darwin"
    exe_suffix = ".exe" if is_win else ""

    if is_win:
        bt_os_tag = "windows"
    elif is_mac:
        bt_os_tag = "macosx"
    else:
        bt_os_tag = "linux"

    # 1. Android API 19 platform (android.jar)
    platform_dir = os.path.join(sdk_dir, "platforms", "android-19")
    android_jar = os.path.join(platform_dir, "android.jar")
    if not os.path.isfile(android_jar):
        print("Provisioning Android API 19 platform (android.jar)...")
        zip_path, is_temp = _fetch_or_cached_zip(
            "https://dl.google.com/android/repository/android-19_r04.zip",
            "android-19_r04.zip",
            cache_dir,
        )
        try:
            with zipfile.ZipFile(zip_path, "r") as z:
                for member in z.infolist():
                    parts = member.filename.split("/", 1)
                    if len(parts) > 1 and parts[1]:
                        target = os.path.join(platform_dir, parts[1])
                        if member.is_dir():
                            os.makedirs(target, exist_ok=True)
                        else:
                            os.makedirs(os.path.dirname(target), exist_ok=True)
                            with open(target, "wb") as f:
                                f.write(z.read(member))
        finally:
            if is_temp and os.path.exists(zip_path):
                os.remove(zip_path)
        print("  android-19 platform installed.")

    # 2. Android Build-Tools 30.0.3
    bt_dir = os.path.join(sdk_dir, "build-tools", "30.0.3")
    aapt_bin = os.path.join(bt_dir, f"aapt{exe_suffix}")
    if not os.path.isfile(aapt_bin):
        print(f"Provisioning Android Build-Tools 30.0.3 ({bt_os_tag})...")
        cache_name = f"build-tools_r30.0.3-{bt_os_tag}.zip"
        zip_path, is_temp = _fetch_or_cached_zip(
            f"https://dl.google.com/android/repository/{cache_name}",
            cache_name,
            cache_dir,
        )
        try:
            with zipfile.ZipFile(zip_path, "r") as z:
                for member in z.infolist():
                    parts = member.filename.split("/", 1)
                    if len(parts) > 1 and parts[1]:
                        target = os.path.join(bt_dir, parts[1])
                        if member.is_dir():
                            os.makedirs(target, exist_ok=True)
                        else:
                            os.makedirs(os.path.dirname(target), exist_ok=True)
                            with open(target, "wb") as f:
                                f.write(z.read(member))
                            if not is_win and ("/" not in parts[1] or parts[1].endswith(".sh")):
                                os.chmod(target, 0o755)
        finally:
            if is_temp and os.path.exists(zip_path):
                os.remove(zip_path)
        print("  build-tools 30.0.3 installed.")

    return sdk_dir


def main() -> None:
    sdk_dir = ensure_sdk()
    is_win = sys.platform.startswith("win")
    exe_suffix = ".exe" if is_win else ""
    bat_suffix = ".bat" if is_win else ""
    bt_dir = os.path.join(sdk_dir, "build-tools", "30.0.3")
    print("SDK Setup Complete:")
    print("  SDK_DIR:    ", sdk_dir)
    print("  android.jar:", os.path.join(sdk_dir, "platforms", "android-19", "android.jar"))
    print("  aapt:       ", os.path.join(bt_dir, f"aapt{exe_suffix}"))
    print("  d8:         ", os.path.join(bt_dir, f"d8{bat_suffix}"))
    print("  zipalign:   ", os.path.join(bt_dir, f"zipalign{exe_suffix}"))


if __name__ == "__main__":
    main()
