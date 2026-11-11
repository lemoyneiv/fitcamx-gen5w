#!/usr/bin/env python3
"""Manage FITCAMX Gen5W signing keys for persistent Cloud Project storage & GitHub Actions Secrets.

Usage:
  python3 tools/manage_keys.py status
  python3 tools/manage_keys.py import [dir_or_files...] [--sync-github-secrets]
"""
from __future__ import annotations

import argparse
import base64
import glob
import json
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cloud_env
import sign

DEFAULT_REPO = "lemoyneiv/fitcamx-gen5w"


def _discover_key_files(paths: list[str]) -> tuple[str | None, str | None, str | None]:
    """Locate (keystore_path, author_key_path, author_pub_path) from files, dirs, or .zip archives."""
    import atexit
    import tempfile
    import zipfile

    search_roots = list(paths)
    if not search_roots:
        for auto_dir in ("/home/antigravity/upload", "/home/antigravity", os.path.join(cloud_env.BASE_DIR, "..", "build", "keys")):
            if os.path.isdir(auto_dir):
                for zf in sorted(glob.glob(os.path.join(auto_dir, "*key*.zip"))):
                    search_roots.append(zf)
                if auto_dir != "/home/antigravity":
                    search_roots.append(auto_dir)

    ks_path = None
    ak_path = None
    ap_path = None

    def _scan_dir(root_dir: str) -> None:
        nonlocal ks_path, ak_path, ap_path
        for cur, _, files in os.walk(root_dir):
            for fn in sorted(files):
                fp = os.path.join(cur, fn)
                if fn == "fitcamx-app.keystore":
                    ks_path = ks_path or fp
                elif fn.endswith((".keystore", ".jks", ".p12")):
                    ks_path = ks_path or fp
                elif fn == "author.key":
                    ak_path = ak_path or fp
                elif fn == "author.key.pub":
                    ap_path = ap_path or fp

    for p in search_roots:
        p = os.path.abspath(os.path.expanduser(p))
        if os.path.isfile(p) and p.lower().endswith(".zip") and zipfile.is_zipfile(p):
            tmp_dir = tempfile.mkdtemp(prefix="fitcamx_key_import_")
            os.chmod(tmp_dir, 0o700)
            atexit.register(lambda d=tmp_dir: shutil.rmtree(d, ignore_errors=True))
            with zipfile.ZipFile(p, "r") as z:
                z.extractall(tmp_dir)
            _scan_dir(tmp_dir)
        elif os.path.isfile(p):
            bn = os.path.basename(p)
            if bn.endswith((".keystore", ".jks", ".p12")):
                ks_path = ks_path or p
            elif bn.endswith(".pub"):
                ap_path = ap_path or p
            elif bn.endswith(".key"):
                ak_path = ak_path or p
        elif os.path.isdir(p):
            _scan_dir(p)

    if ak_path and not ap_path and os.path.isfile(ak_path + ".pub"):
        ap_path = ak_path + ".pub"
    return ks_path, ak_path, ap_path


def cmd_status(args: argparse.Namespace) -> int:
    jdk = cloud_env.resolve_jdk()
    print("=== FITCAMX Gen5W Signing Key Status ===")
    print(f"Expected Release HMG-API UUID : {cloud_env.EXPECTED_RELEASE_UUID}")
    print(f"Expected APK Cert SHA-256     : {cloud_env.EXPECTED_APK_CERT_SHA256}")
    print(f"Persistent Cloud Keys Path    : {cloud_env.get_persistent_keys_dir()}")
    print()

    keys = cloud_env.resolve_signing_keys(jdk.keytool, require_release=False)
    print(f"Active Key Source   : {keys.source}")
    print(f"Active HMG-API UUID : {keys.author_uuid}")
    print(f"Active APK SHA-256  : {keys.apk_cert_sha256}")
    if keys.matches_release_identity:
        print("Identity Check      : [MATCH] Matches v0.7.0 production release keys (head-unit upgrades supported)")
    elif keys.is_ephemeral:
        print("Identity Check      : [DEV] Using auto-generated ephemeral dev keys (upload release keys for upgrades)")
    else:
        print("Identity Check      : [CUSTOM] Keys loaded, but differ from v0.7.0 release identity")

    if shutil.which("gh"):
        print(f"\nGitHub Actions Secrets ({args.repo}):")
        res = subprocess.run(
            ["gh", "secret", "list", "-R", args.repo],
            capture_output=True,
            text=True,
            env=cloud_env.plugin_cli_env(),
        )
        if res.returncode == 0:
            lines = [ln.strip() for ln in res.stdout.strip().splitlines() if ln.strip()]
            if lines:
                for ln in lines:
                    print(f"  {ln}")
            else:
                print("  (no repository secrets configured yet)")
        else:
            print(f"  (unable to query secrets: {res.stderr.strip() or 'check gh auth'})")
    return 0


def _set_gh_secret(repo: str, name: str, value: str) -> None:
    subprocess.run(
        ["gh", "secret", "set", name, "-R", repo],
        input=value,
        text=True,
        check=True,
        env=cloud_env.plugin_cli_env(),
    )
    print(f"  [OK] Set GitHub Actions secret: {name}")


def cmd_import(args: argparse.Namespace) -> int:
    jdk = cloud_env.resolve_jdk()
    ks_src, ak_src, ap_src = _discover_key_files(args.paths)
    if not ks_src or not ak_src:
        print("ERROR: Could not locate both `fitcamx-app.keystore` and `author.key`.", file=sys.stderr)
        print("  Found keystore  :", ks_src or "(missing)", file=sys.stderr)
        print("  Found author.key:", ak_src or "(missing)", file=sys.stderr)
        print("\nTip: Drag & drop `fitcamx-app.keystore` and `author.key` into the chat (staged in /home/antigravity/upload)", file=sys.stderr)
        print("     and run: python3 tools/manage_keys.py import --sync-github-secrets", file=sys.stderr)
        return 1

    storepass = args.keystore_pass
    alias = args.alias
    keypass = args.key_pass or storepass

    # 1. Validate keystore & extract cert SHA-256
    try:
        cert_fp = cloud_env.keystore_cert_sha256(jdk.keytool, ks_src, storepass, alias)
    except subprocess.CalledProcessError as exc:
        print(
            f"ERROR: Failed to unlock keystore {ks_src} (alias='{alias}'). Check --keystore-pass / --alias.\n"
            f"  {exc.stderr.decode('utf-8', errors='replace').strip()}",
            file=sys.stderr,
        )
        return 1

    # 2. Validate author.key & derive/verify author.key.pub + ECDSA signature round-trip
    priv_b64 = "".join(open(ak_src, "r", encoding="utf-8").read().split())
    priv_der = base64.b64decode(priv_b64)
    derived_pub_der = cloud_env.derive_author_pub_der(priv_der)
    if ap_src and os.path.isfile(ap_src):
        supplied_pub_der = base64.b64decode("".join(open(ap_src, "r", encoding="utf-8").read().split()))
        if supplied_pub_der != derived_pub_der:
            print("ERROR: Supplied author.key.pub does not match private key author.key!", file=sys.stderr)
            return 1
    pub_der = derived_pub_der
    pub_b64 = base64.b64encode(pub_der).decode("ascii")
    uuid = cloud_env.author_uuid_from_pub_der(pub_der)

    # Verify ECDSA signing works end-to-end
    sign.sign_manifest(ak_src, pub_der, b'{"test":true}\n')

    matches_v070 = (
        uuid == cloud_env.EXPECTED_RELEASE_UUID
        and cert_fp == cloud_env.EXPECTED_APK_CERT_SHA256
    )
    if not matches_v070 and not args.allow_new_identity:
        print("ERROR: Supplied keys do NOT match the v0.7.0 release signing identity:", file=sys.stderr)
        print(f"  HMG-API UUID : got {uuid} (expected {cloud_env.EXPECTED_RELEASE_UUID})", file=sys.stderr)
        print(f"  APK SHA-256  : got {cert_fp} (expected {cloud_env.EXPECTED_APK_CERT_SHA256})", file=sys.stderr)
        print("Pass --allow-new-identity only if you intentionally want a new signing identity.", file=sys.stderr)
        return 1

    # 3. Persist into ~/.gemini/fitcamx-gen5w/keys/
    dest_dir = cloud_env.get_persistent_keys_dir()
    ks_dst = os.path.join(dest_dir, "fitcamx-app.keystore")
    ak_dst = os.path.join(dest_dir, "author.key")
    ap_dst = os.path.join(dest_dir, "author.key.pub")
    meta_dst = os.path.join(dest_dir, "keystore.json")

    cloud_env.copy_bytes_safe(ks_src, ks_dst)
    cloud_env.write_bytes_safe(ak_dst, (priv_b64 + "\n").encode("ascii"))
    cloud_env.write_bytes_safe(ap_dst, (pub_b64 + "\n").encode("ascii"))
    cloud_env.write_bytes_safe(
        meta_dst,
        (json.dumps({"storepass": storepass, "alias": alias, "keypass": keypass}, indent=2) + "\n").encode("utf-8"),
    )

    print(f"Successfully imported signing keys into persistent cloud storage: {dest_dir}")
    print(f"  HMG-API UUID : {uuid}")
    print(f"  APK SHA-256  : {cert_fp}")
    print(f"  v0.7.0 Match : {'YES' if matches_v070 else 'NO (custom identity)'}")

    # 4. Optionally sync to GitHub Actions Secrets
    if args.sync_github_secrets:
        print(f"\nSyncing signing secrets to GitHub repository {args.repo} ...")
        ks_b64 = base64.b64encode(open(ks_src, "rb").read()).decode("ascii")
        _set_gh_secret(args.repo, "FITCAMX_KEYSTORE_BASE64", ks_b64)
        _set_gh_secret(args.repo, "FITCAMX_KEYSTORE_PASS", storepass)
        _set_gh_secret(args.repo, "FITCAMX_KEY_ALIAS", alias)
        _set_gh_secret(args.repo, "FITCAMX_KEY_PASS", keypass)
        _set_gh_secret(args.repo, "FITCAMX_AUTHOR_KEY", priv_b64)
        _set_gh_secret(args.repo, "FITCAMX_AUTHOR_PUB", pub_b64)
        print("All GitHub Actions release signing secrets configured!")

    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Manage FITCAMX Gen5W cloud signing keys.")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_status = sub.add_parser("status", help="Inspect active signing keys and GitHub Secrets status")
    p_status.add_argument("--repo", default=DEFAULT_REPO, help="GitHub owner/repo")
    p_status.set_defaults(func=cmd_status)

    p_imp = sub.add_parser("import", help="Validate and import signing keys into persistent cloud storage")
    p_imp.add_argument("paths", nargs="*", help="Key files or directory (defaults to /home/antigravity/upload)")
    p_imp.add_argument("--keystore-pass", default="fitcamx-app", help="Keystore password (default: fitcamx-app)")
    p_imp.add_argument("--alias", default="fitcamx", help="Keystore key alias (default: fitcamx)")
    p_imp.add_argument("--key-pass", default="", help="Key password if different from keystore password")
    p_imp.add_argument("--sync-github-secrets", action="store_true", help="Also upload secrets to GitHub Actions")
    p_imp.add_argument("--repo", default=DEFAULT_REPO, help="GitHub owner/repo for --sync-github-secrets")
    p_imp.add_argument("--allow-new-identity", action="store_true", help="Allow keys differing from v0.7.0 identity")
    p_imp.set_defaults(func=cmd_import)

    args = parser.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
