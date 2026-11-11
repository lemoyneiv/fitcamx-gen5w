#!/usr/bin/env python3
"""Sync built FITCAMX Gen5W ZIP packages to Google Drive via the connected gdrive CLI.

Usage:
  python3 tools/sync_gdrive.py                     # Sync production-signed dist/*.zip
  python3 tools/sync_gdrive.py --usb-only          # Sync only *-usb.zip flash-drive packages
  python3 tools/sync_gdrive.py --allow-dev-keys    # Sync dev-signed packages into dev-builds/ subfolder
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cloud_env

DEFAULT_FOLDER_NAME = "FITCAMX Gen5W Builds"


def _run_gdrive(args: list[str], parse_json: bool = False):
    if not shutil.which("gdrive"):
        sys.exit("ERROR: `gdrive` CLI is not installed or not on PATH.")
    cmd = ["gdrive"] + args + (["--json"] if parse_json and "--json" not in args else [])
    res = subprocess.run(cmd, capture_output=True, text=True, env=cloud_env.plugin_cli_env())
    if res.returncode != 0:
        sys.exit(f"ERROR running `{' '.join(cmd)}`:\n{res.stderr.strip() or res.stdout.strip()}")
    if parse_json:
        out = res.stdout.strip()
        if not out:
            return None
        try:
            return json.loads(out)
        except json.JSONDecodeError:
            return out
    return (res.stdout + "\n" + res.stderr).strip()


def _extract_id(obj) -> str:
    if isinstance(obj, dict):
        return obj.get("id") or obj.get("ID") or obj.get("fileId") or ""
    if isinstance(obj, list) and obj:
        return _extract_id(obj[0])
    if isinstance(obj, str):
        import re
        m = re.search(r"(?:ID|id|folders/|/d/)[:=\s]*([A-Za-z0-9_-]{15,})", obj)
        if m:
            return m.group(1)
    return ""


def _folder_valid(folder_id: str) -> bool:
    if not folder_id:
        return False
    res = subprocess.run(
        ["gdrive", "readonly", "info", folder_id, "--json"],
        capture_output=True,
        text=True,
        env=cloud_env.plugin_cli_env(),
    )
    if res.returncode != 0:
        return False
    try:
        info = json.loads(res.stdout)
        if isinstance(info, dict) and info.get("trashed") is True:
            return False
    except json.JSONDecodeError:
        pass
    return True


def _ensure_folder(name: str, parent_id: str | None = None, use_saved_config: bool = False) -> str:
    cfg = cloud_env.load_cloud_config() if use_saved_config else {}
    if use_saved_config:
        saved_id = os.environ.get("FITCAMX_GDRIVE_FOLDER_ID") or cfg.get("gdrive_folder_id", "")
        if saved_id and _folder_valid(saved_id):
            return saved_id

    search_args = ["readonly", "search", "--name-exact", name, "--type", "folder"]
    if parent_id:
        search_args.extend(["--parent-id", parent_id])
    found = _run_gdrive(search_args, parse_json=True) or []
    if isinstance(found, dict):
        found = found.get("files", [])
    for item in found:
        if isinstance(item, dict) and not item.get("trashed", False):
            fid = _extract_id(item)
            if fid:
                if use_saved_config:
                    cfg["gdrive_folder_id"] = fid
                    cfg["gdrive_folder_name"] = name
                    cloud_env.save_cloud_config(cfg)
                return fid

    mkdir_args = ["mutate", "mkdir", name]
    if parent_id:
        mkdir_args.extend(["--parent", parent_id])
    created = _run_gdrive(mkdir_args, parse_json=False)
    fid = _extract_id(created)
    if not fid:
        # Fallback lookup if CLI returned non-dict confirmation
        found_after = _run_gdrive(search_args, parse_json=True) or []
        fid = _extract_id(found_after)
    if not fid:
        sys.exit(f"ERROR: Could not determine folder ID after creating '{name}'.")

    if use_saved_config:
        cfg["gdrive_folder_id"] = fid
        cfg["gdrive_folder_name"] = name
        cloud_env.save_cloud_config(cfg)
    return fid


def _inspect_package_uuid(zip_files: list[str]) -> str | None:
    """Read HMG-API author UUID from .hmgapi.zip (or nested USB zip) to verify release vs dev signing."""
    for zp in zip_files:
        if zp.endswith(".hmgapi.zip"):
            with zipfile.ZipFile(zp, "r") as z:
                manifest = json.loads(z.read("META-HMGAPI/manifest.json"))
                return manifest.get("uuid")
    for zp in zip_files:
        if zp.endswith("-hmgapi-usb.zip"):
            with zipfile.ZipFile(zp, "r") as outer:
                inner_names = [n for n in outer.namelist() if n.endswith(".zip")]
                if inner_names:
                    import io
                    with zipfile.ZipFile(io.BytesIO(outer.read(inner_names[0])), "r") as inner:
                        manifest = json.loads(inner.read("META-HMGAPI/manifest.json"))
                        return manifest.get("uuid")
    return None


def sync_packages(
    src_dir: str,
    folder_id: str | None = None,
    folder_name: str = DEFAULT_FOLDER_NAME,
    usb_only: bool = False,
    allow_dev_keys: bool = False,
) -> tuple[str, list[dict]]:
    # 1. Verify gdrive auth status first
    auth_out = _run_gdrive(["auth", "status"], parse_json=False)
    if "Logged in" not in auth_out:
        sys.exit(f"ERROR: Google Drive is not logged in:\n{auth_out}")

    pattern = "fitcamx-gen5w-v*-usb.zip" if usb_only else "fitcamx-gen5w-v*.zip"
    zip_files = sorted(glob.glob(os.path.join(src_dir, pattern)))
    if not zip_files:
        sys.exit(f"ERROR: No matching packages ({pattern}) found in {src_dir}. Run `./build.sh` first.")

    pkg_uuid = _inspect_package_uuid(zip_files)
    is_release_signed = pkg_uuid == cloud_env.EXPECTED_RELEASE_UUID
    if not is_release_signed and not allow_dev_keys:
        sys.exit(
            f"REFUSED: Packages in {src_dir} are signed with non-release UUID ({pkg_uuid}),\n"
            f"  expected production UUID {cloud_env.EXPECTED_RELEASE_UUID}.\n"
            f"  Import your release keys (`python3 tools/manage_keys.py import`) and rebuild,\n"
            f"  or pass `--allow-dev-keys` to upload into the `dev-builds/` subfolder."
        )

    root_folder_id = (
        folder_id
        if (folder_id and _folder_valid(folder_id))
        else _ensure_folder(folder_name, parent_id=None, use_saved_config=(folder_name == DEFAULT_FOLDER_NAME))
    )
    target_folder_id = (
        root_folder_id
        if is_release_signed
        else _ensure_folder("dev-builds", parent_id=root_folder_id, use_saved_config=False)
    )

    folder_url = f"https://drive.google.com/drive/folders/{target_folder_id}"
    print(f"=== Syncing FITCAMX Packages to Google Drive ===")
    print(f"Target Folder : {folder_name}{'' if is_release_signed else '/dev-builds'} ({target_folder_id})")
    print(f"Folder URL    : {folder_url}")
    print(f"Signing Mode  : {'RELEASE (' + str(pkg_uuid) + ')' if is_release_signed else 'DEV (' + str(pkg_uuid) + ')'}")

    existing_items = _run_gdrive(
        ["readonly", "search", "--parent-id", target_folder_id, "--max", "100"],
        parse_json=True,
    ) or []
    if isinstance(existing_items, dict):
        existing_items = existing_items.get("files", [])
    existing_by_name = {}
    for item in existing_items:
        if isinstance(item, dict) and not item.get("trashed", False):
            nm = item.get("name") or item.get("Name")
            fid = _extract_id(item)
            if nm and fid:
                existing_by_name[nm] = fid

    results = []
    for zp in zip_files:
        fname = os.path.basename(zp)
        size_mb = os.path.getsize(zp) / (1024 * 1024)
        if fname in existing_by_name:
            fid = existing_by_name[fname]
            print(f"  Updating {fname} ({size_mb:.2f} MB) in-place [{fid}] ...")
            _run_gdrive(["mutate", "update", fid, zp, "--mime-type", "application/zip"], parse_json=False)
            action = "updated"
        else:
            print(f"  Uploading {fname} ({size_mb:.2f} MB) ...")
            up = _run_gdrive(
                ["mutate", "upload", zp, "--parent", target_folder_id, "--mime-type", "application/zip"],
                parse_json=False,
            )
            fid = _extract_id(up)
            action = "uploaded"
        results.append({"name": fname, "id": fid, "action": action, "bytes": os.path.getsize(zp)})

    print(f"\nSynced {len(results)} package(s) to Google Drive: {folder_url}")
    return folder_url, results


def main() -> None:
    parser = argparse.ArgumentParser(description="Sync FITCAMX Gen5W release archives to Google Drive.")
    parser.add_argument(
        "--src-dir",
        default=os.path.join(cloud_env.BASE_DIR, "dist"),
        help="Directory containing built .zip packages (default: dist/)",
    )
    parser.add_argument("--folder-id", default="", help="Explicit Google Drive destination folder ID")
    parser.add_argument("--folder-name", default=DEFAULT_FOLDER_NAME, help="Drive folder name")
    parser.add_argument("--usb-only", action="store_true", help="Upload only *-usb.zip packages")
    parser.add_argument(
        "--allow-dev-keys",
        action="store_true",
        help="Allow uploading dev-key builds (placed inside a dev-builds/ subfolder)",
    )
    args = parser.parse_args()
    sync_packages(
        src_dir=os.path.abspath(args.src_dir),
        folder_id=args.folder_id or None,
        folder_name=args.folder_name,
        usb_only=args.usb_only,
        allow_dev_keys=args.allow_dev_keys,
    )


if __name__ == "__main__":
    main()
