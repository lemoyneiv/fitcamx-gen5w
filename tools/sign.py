#!/usr/bin/env python3
"""Sign a staged mod folder and build the universal USB installer, HMG-API mod zip, and NEMESIS package.

    tools/sign.py <mod dir> <dist dir> <key> [hmg-api checkout]

Outputs in <dist dir>:
  1. <id>-v<version>-usb.zip              Single universal USB installer (works with HMG-API AND standalone NEMESIS)
  2. <id>-v<version>.hmgapi.zip           Signed HMG-API mod package (ECDSA SHA-256 over META-HMGAPI/manifest.json)
  3. <id>-v<version>-nemesis-package.zip  Adaptive NEMESIS package (id=fitcamx.gen5w, auto-detects HMG-API vs standalone)
"""
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
NEMESIS_TPL_DIR = os.path.join(REPO_ROOT, "nemesis")
NEMESIS_PACKAGE_ID = "fitcamx.gen5w"
META = "META-HMGAPI"


def openssl(*args, data=None):
    return subprocess.run(["openssl", *args], input=data, capture_output=True, check=True).stdout


def der_of(path):
    with open(path) as f:
        return base64.b64decode(f.read().strip())


def uuid_of(pub_der):
    d = bytearray(hashlib.sha256(pub_der).digest())
    d[6] = (d[6] & 15) | 128
    d[8] = (d[8] & 63) | 128
    h = d[:16].hex()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def files_of(root):
    out = []
    for dirpath, dirs, names in os.walk(root):
        rel = os.path.relpath(dirpath, root).replace("\\", "/")
        dirs[:] = [d for d in dirs if not (rel == "." and d == META)]
        for n in names:
            if n == ".DS_Store" or (rel == "." and n == ".disabled"):
                continue
            out.append(n if rel == "." else f"{rel}/{n}")
    return sorted(out)


def nemesis_package_version(ver: str) -> str:
    """Convert semver to NEMESIS dotted numeric package-version (matching HMG-API tools/nemesis-version.py)."""
    if re.fullmatch(r"[0-9]+(\.[0-9]+)*", ver):
        return ver
    m = re.fullmatch(r"([0-9]+)\.([0-9]+)\.([0-9]+)-(alpha|beta|rc)\.(0|[1-9][0-9]*)", ver)
    if not m:
        sys.exit(f"Unsupported version format for NEMESIS package-version: {ver}")
    x, y, z = int(m.group(1)), int(m.group(2)), int(m.group(3))
    tag = {"alpha": "97", "beta": "98", "rc": "99"}[m.group(4)]
    n = m.group(5)
    if z > 0:
        return f"{x}.{y}.{z - 1}.{tag}.{n}"
    if y > 0:
        return f"{x}.{y - 1}.{tag}.{n}"
    if x > 0:
        return f"{x - 1}.{tag}.{n}"
    sys.exit(f"Cannot compute pre-release NEMESIS package-version before 0.0.0: {ver}")


def _clean_text(s: str, max_len: int) -> str:
    cleaned = " ".join(str(s).replace('"', "'").split())
    return cleaned if len(cleaned) <= max_len else cleaned[: max_len - 3].rstrip() + "..."


def _add_zip_entry(z: zipfile.ZipFile, arcname: str, data: bytes, exec_bit: bool = False, stored: bool = False):
    zi = zipfile.ZipInfo(arcname, (2026, 1, 1, 0, 0, 0))
    zi.create_system = 3  # Unix permissions
    zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    zi.external_attr = (0o100755 if exec_bit else 0o100644) << 16
    z.writestr(zi, data)


def _read_lf_bytes(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read().replace(b"\r\n", b"\n")


def build_adaptive_packages(signed_hmgapi_zip: str, mod: dict, mod_dir: str, dist: str):
    """Build the adaptive NEMESIS package and the single universal USB zip."""
    mid = mod["id"]
    ver = mod["version"]
    pkg_id = NEMESIS_PACKAGE_ID
    pv = nemesis_package_version(ver)

    name = _clean_text(mod.get("name") or mid, 64)
    short_desc = _clean_text("FITCAMX dashcam & backup camera viewer (HMG-API or NEMESIS)", 64)
    detail_desc = _clean_text(
        f"{name} {ver}: live RTSP stream, OEM car backup camera integration, SD/USB gallery & export. "
        f"Installs automatically through HMG-API when present, or standalone on NEMESIS.",
        256,
    )

    repl = {
        "ID": mid,
        "VERSION": ver,
        "PACKAGE_ID": pkg_id,
        "PACKAGE_VERSION": pv,
        "NAME": name,
        "SHORT_DESC": short_desc,
        "DETAILED_DESC": detail_desc,
    }

    def fill_tpl(filename: str) -> bytes:
        txt = open(os.path.join(NEMESIS_TPL_DIR, filename), "r", encoding="utf-8").read()
        for k, v in repl.items():
            txt = txt.replace(f"@{k}@", v)
        return txt.replace("\r\n", "\n").encode("utf-8")

    open_sh_path = os.path.join(mod_dir, "open.sh")
    service_sh_path = os.path.join(NEMESIS_TPL_DIR, "bin", "service.sh")
    rearcam_jar_path = os.path.join(mod_dir, "svc", "rearcam.jar")
    if not os.path.isfile(rearcam_jar_path):
        sys.exit(f"Missing required {rearcam_jar_path} for adaptive NEMESIS package")

    signed_bytes = open(signed_hmgapi_zip, "rb").read()
    rearcam_bytes = open(rearcam_jar_path, "rb").read()

    # 1. Adaptive NEMESIS package (<id>-v<ver>-nemesis-package.zip)
    nemesis_pkg_path = os.path.join(dist, f"{mid}-v{ver}-nemesis-package.zip")
    pkg_entries = [
        ("NEMESIS.data", fill_tpl("NEMESIS.data.in"), False, False),
        ("bin/open.sh", _read_lf_bytes(open_sh_path), True, False),
        ("bin/rearcam.jar", rearcam_bytes, False, True),
        ("bin/service.sh", _read_lf_bytes(service_sh_path), True, False),
        ("mod/id", (mid + "\n").encode("utf-8"), False, False),
        ("mod/package.zip", signed_bytes, False, True),
    ]
    scripts_dir = os.path.join(NEMESIS_TPL_DIR, "scripts")
    for fn in sorted(os.listdir(scripts_dir)):
        fp = os.path.join(scripts_dir, fn)
        if os.path.isfile(fp):
            pkg_entries.append((f"scripts/{fn}", _read_lf_bytes(fp), fn.endswith(".sh"), False))

    with zipfile.ZipFile(nemesis_pkg_path, "w", zipfile.ZIP_DEFLATED) as z:
        for arcname, data, exec_bit, stored in sorted(pkg_entries, key=lambda x: x[0]):
            _add_zip_entry(z, arcname, data, exec_bit=exec_bit, stored=stored)

    # 2. Single Universal USB Installer (<id>-v<ver>-usb.zip)
    #    - HMGAPI/updates/<id>-v<ver>.zip  (scanned by HMG-API app -> Updates)
    #    - NEMESIS.data + packages/fitcamx.gen5w.zip + README.txt  (scanned by NEMESIS Manager USB install)
    nemesis_pkg_bytes = open(nemesis_pkg_path, "rb").read()
    universal_usb_path = os.path.join(dist, f"{mid}-v{ver}-usb.zip")
    usb_descriptor = f"[nemesis]\nversion=1.1\ntype=install\npath=packages/{pkg_id}.zip\n".encode("utf-8")
    usb_entries = [
        (f"HMGAPI/updates/{mid}-v{ver}.zip", signed_bytes, False, True),
        ("NEMESIS.data", usb_descriptor, False, False),
        ("README.txt", fill_tpl("README.txt.in"), False, False),
        (f"packages/{pkg_id}.zip", nemesis_pkg_bytes, False, True),
    ]
    with zipfile.ZipFile(universal_usb_path, "w", zipfile.ZIP_DEFLATED) as z:
        for arcname, data, exec_bit, stored in usb_entries:
            _add_zip_entry(z, arcname, data, exec_bit=exec_bit, stored=stored)

    return universal_usb_path, nemesis_pkg_path


def sign_manifest(key, pub, manifest):
    if shutil.which("openssl"):
        with tempfile.TemporaryDirectory() as tmp:
            kpem = os.path.join(tmp, "k.pem")
            fd = os.open(kpem, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(openssl("pkey", "-inform", "DER", data=der_of(key)))
            sig = openssl("dgst", "-sha256", "-sign", kpem, data=manifest)
            ppem = os.path.join(tmp, "p.pem")
            sigf = os.path.join(tmp, "sig")
            open(ppem, "wb").write(openssl("pkey", "-pubin", "-inform", "DER", data=pub))
            open(sigf, "wb").write(sig)
            openssl("dgst", "-sha256", "-verify", ppem, "-signature", sigf, data=manifest)
            return sig
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    priv_key = serialization.load_der_private_key(der_of(key), password=None)
    pub_key = serialization.load_der_public_key(pub)
    sig = priv_key.sign(manifest, ec.ECDSA(hashes.SHA256()))
    pub_key.verify(sig, manifest, ec.ECDSA(hashes.SHA256()))
    return sig


def build(mod_dir, dist, key, hmgapi=None):
    pub_path = key + ".pub"
    if not os.path.isfile(pub_path):
        sys.exit(f"no {pub_path}")
    pub = der_of(pub_path)
    mod = json.load(open(os.path.join(mod_dir, "mod.json"), encoding="utf-8"))
    files = {n: hashlib.sha256(open(os.path.join(mod_dir, n), "rb").read()).hexdigest() for n in files_of(mod_dir)}
    manifest = json.dumps({
        "format": 1, "id": mod["id"], "version": mod["version"],
        "uuid": uuid_of(pub), "key": base64.b64encode(pub).decode(), "files": files,
    }, indent=2).encode() + b"\n"

    sig = sign_manifest(key, pub, manifest)

    os.makedirs(dist, exist_ok=True)
    hmgapi_out = os.path.join(dist, f"{mod['id']}-v{mod['version']}.hmgapi.zip")
    with zipfile.ZipFile(hmgapi_out, "w", zipfile.ZIP_DEFLATED) as z:
        _add_zip_entry(z, f"{META}/manifest.json", manifest, exec_bit=False)
        _add_zip_entry(z, f"{META}/signature", (base64.b64encode(sig).decode() + "\n").encode("utf-8"), exec_bit=False)
        for n in files:
            src_p = os.path.join(mod_dir, n)
            raw = _read_lf_bytes(src_p) if n.endswith(".sh") else open(src_p, "rb").read()
            _add_zip_entry(z, n, raw, exec_bit=n.endswith(".sh"), stored=n.endswith((".apk", ".jar")))

    universal_usb, nemesis_pkg = build_adaptive_packages(hmgapi_out, mod, mod_dir, dist)
    print(f"signed {mod['id']} {mod['version']}: {len(files)} files, uuid {uuid_of(pub)}")
    print(universal_usb)
    print(hmgapi_out)
    print(nemesis_pkg)


if __name__ == "__main__":
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    build(*sys.argv[1:])
