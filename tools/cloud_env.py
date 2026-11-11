#!/usr/bin/env python3
"""Cloud & local environment provisioning, persistent caching, and signing key resolution."""
from __future__ import annotations

import atexit
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

# Production release identity from v0.7.0 (preserves in-place head-unit upgrades)
EXPECTED_RELEASE_UUID = "7abe7eda-b784-851a-bb6e-91cb0294bdf1"
EXPECTED_APK_CERT_SHA256 = "eb79685de0c2b5ad8bc28fc490c01b2a1cd00672fb515b6c615085df091d2269"
HMGAPI_REPO_URL = "https://github.com/VysseCodes/HMG-API.git"


@dataclass
class JdkToolchain:
    java_home: str
    java: str
    javac: str
    keytool: str


@dataclass
class SigningKeys:
    keystore_path: str
    keystore_pass: str
    key_alias: str
    key_pass: str
    author_key_path: str
    author_pub_path: str
    author_uuid: str
    apk_cert_sha256: str
    source: str
    is_ephemeral: bool
    matches_release_identity: bool


def get_cloud_home() -> str:
    """Return persistent directory across cloud sessions (~/.gemini/fitcamx-gen5w when available)."""
    if os.environ.get("FITCAMX_CLOUD_HOME"):
        return os.path.abspath(os.environ["FITCAMX_CLOUD_HOME"])
    gemini_root = os.path.expanduser("~/.gemini")
    if os.path.isdir(gemini_root):
        return os.path.join(gemini_root, "fitcamx-gen5w")
    return os.path.expanduser("~/.config/fitcamx-gen5w")


def get_cache_dir() -> str:
    if os.environ.get("FITCAMX_CACHE_DIR"):
        return os.path.abspath(os.environ["FITCAMX_CACHE_DIR"])
    gemini_root = os.path.expanduser("~/.gemini")
    if os.path.isdir(gemini_root):
        return os.path.join(gemini_root, "fitcamx-gen5w", "cache")
    return os.path.expanduser("~/.cache/fitcamx-gen5w")


def get_persistent_keys_dir() -> str:
    return os.path.join(get_cloud_home(), "keys")


def get_cloud_config_path() -> str:
    return os.path.join(get_cloud_home(), "config.json")


def write_bytes_safe(path: str, data: bytes) -> None:
    """Write bytes without calling chmod/utime (compatible with /shared/user/.gemini FUSE mounts)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def copy_bytes_safe(src: str, dst: str) -> None:
    """Copy file contents without copying stat/mode metadata."""
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(src, "rb") as rf, open(dst, "wb") as wf:
        while True:
            chunk = rf.read(1024 * 1024)
            if not chunk:
                break
            wf.write(chunk)


def load_cloud_config() -> dict:
    cfg_path = get_cloud_config_path()
    if os.path.isfile(cfg_path):
        try:
            with open(cfg_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except (OSError, json.JSONDecodeError):
            return {}
    return {}


def save_cloud_config(cfg: dict) -> None:
    payload = (json.dumps(cfg, indent=2, sort_keys=True) + "\n").encode("utf-8")
    write_bytes_safe(get_cloud_config_path(), payload)


def plugin_cli_env() -> dict[str, str]:
    """Return environment suitable for invoking Antigravity plugin CLIs (gh, gdrive).

    On Antigravity Linux VMs, ANTIGRAVITY_APP_DATA_DIR defaults to ~/.gemini/jetski on a shared
    mount that rejects chmod/utime when the plugin launcher downloads its pinned CLI binary on
    first run. Redirecting ANTIGRAVITY_APP_DATA_DIR to a local directory while preserving
    GITHUB_TOKEN_FILE / proxy variables lets the official launcher cache binaries cleanly.
    """
    env = os.environ.copy()
    app_data = env.get("ANTIGRAVITY_APP_DATA_DIR", "")
    if not sys.platform.startswith("win") and "/.gemini/" in app_data:
        if "GITHUB_TOKEN_FILE" not in env:
            default_tok = os.path.join(app_data, "plugin_data", "github", "token.json")
            if os.path.isfile(default_tok):
                env["GITHUB_TOKEN_FILE"] = default_tok
        local_cli_dir = os.path.expanduser("~/.local/share/agy-cli")
        os.makedirs(local_cli_dir, exist_ok=True)
        env["ANTIGRAVITY_APP_DATA_DIR"] = local_cli_dir
    return env


def resolve_jdk() -> JdkToolchain:
    """Locate JDK 8/17+ across Linux/macOS/Windows without setting invalid JAVA_HOME paths."""
    is_win = sys.platform.startswith("win")
    exe_suffix = ".exe" if is_win else ""

    java_home = os.environ.get("JAVA_HOME", "")
    if not java_home or not os.path.isdir(java_home):
        candidates = [
            os.path.abspath(os.path.join(BASE_DIR, "..", "tools", "jdk-17")),
            os.path.expanduser("~/tools/jdk-17"),
            r"C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot",
        ]
        javac_on_path = shutil.which("javac")
        if javac_on_path:
            real_javac = os.path.realpath(javac_on_path)
            bin_dir = os.path.dirname(real_javac)
            if os.path.basename(bin_dir).lower() == "bin":
                candidates.insert(0, os.path.dirname(bin_dir))
        java_home = next((d for d in candidates if os.path.isdir(d)), "")

    jdk_bin = os.path.join(java_home, "bin") if java_home else ""

    def _tool(name: str) -> str:
        cand = os.path.join(jdk_bin, f"{name}{exe_suffix}") if jdk_bin else ""
        if cand and os.path.isfile(cand):
            return cand
        return shutil.which(name) or name

    return JdkToolchain(
        java_home=java_home,
        java=_tool("java"),
        javac=_tool("javac"),
        keytool=_tool("keytool"),
    )


def ensure_android_sdk() -> str:
    """Resolve or auto-provision Android API 19 platform + Build-Tools 30.0.3."""
    is_win = sys.platform.startswith("win")
    exe_suffix = ".exe" if is_win else ""

    env_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates = []
    if env_sdk:
        candidates.append(os.path.abspath(env_sdk))
    candidates.extend([
        os.path.abspath(os.path.join(BASE_DIR, "..", "tools", "android-sdk")),
        os.path.expanduser("~/tools/android-sdk"),
    ])

    for cand in candidates:
        jar = os.path.join(cand, "platforms", "android-19", "android.jar")
        aapt = os.path.join(cand, "build-tools", "30.0.3", f"aapt{exe_suffix}")
        if os.path.isfile(jar) and os.path.isfile(aapt):
            return cand

    target_sdk = os.path.abspath(env_sdk) if env_sdk else os.path.expanduser("~/tools/android-sdk")
    if BASE_DIR not in sys.path:
        sys.path.insert(0, os.path.join(BASE_DIR, "tools"))
    import setup_android_sdk

    return setup_android_sdk.ensure_sdk(sdk_dir=target_sdk, cache_dir=get_cache_dir())


def ensure_hmgapi() -> str:
    """Resolve or auto-provision VysseCodes/HMG-API checkout for NEMESIS packaging & script checks."""
    candidates = []
    if os.environ.get("FITCAMX_HMGAPI_DIR"):
        candidates.append(os.path.abspath(os.environ["FITCAMX_HMGAPI_DIR"]))
    candidates.extend([
        os.path.abspath(os.path.join(BASE_DIR, "third_party", "HMG-API")),
        os.path.abspath(os.path.join(BASE_DIR, "..", "third_party", "HMG-API")),
        os.path.abspath(os.path.join(BASE_DIR, "..", "..", "HMG-API")),
        os.path.expanduser("~/tools/HMG-API"),
    ])

    for cand in candidates:
        if os.path.isfile(os.path.join(cand, "tools", "mknemesismod.sh")):
            return cand

    target_dir = (
        os.path.abspath(os.environ["FITCAMX_HMGAPI_DIR"])
        if os.environ.get("FITCAMX_HMGAPI_DIR")
        else os.path.expanduser("~/tools/HMG-API")
    )
    cache_zip = os.path.join(get_cache_dir(), "hmg-api-main.zip")

    if os.path.isfile(cache_zip) and zipfile.is_zipfile(cache_zip):
        print(f"Restoring HMG-API from persistent cache ({cache_zip})...")
        os.makedirs(target_dir, exist_ok=True)
        with zipfile.ZipFile(cache_zip, "r") as z:
            for member in z.infolist():
                if member.is_dir():
                    continue
                dest = os.path.join(target_dir, member.filename)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as wf:
                    wf.write(z.read(member))
                if not sys.platform.startswith("win") and member.filename.endswith((".sh", ".py")):
                    os.chmod(dest, 0o755)
        return target_dir

    print(f"Cloning HMG-API ({HMGAPI_REPO_URL}) into {target_dir} ...")
    shutil.rmtree(target_dir, ignore_errors=True)
    os.makedirs(os.path.dirname(target_dir), exist_ok=True)
    subprocess.run(
        ["git", "clone", "--depth", "1", HMGAPI_REPO_URL, target_dir],
        check=True,
        env=plugin_cli_env(),
    )

    # Persist lightweight zip snapshot (excluding .git) for instant restore in future cloud VMs
    try:
        fd, tmp_zip = tempfile.mkstemp(prefix="hmgapi_cache_", suffix=".zip")
        os.close(fd)
        try:
            with zipfile.ZipFile(tmp_zip, "w", zipfile.ZIP_DEFLATED) as z:
                for root, dirs, files in os.walk(target_dir):
                    dirs[:] = [d for d in dirs if d != ".git"]
                    for fn in sorted(files):
                        fp = os.path.join(root, fn)
                        rel = os.path.relpath(fp, target_dir).replace("\\", "/")
                        z.write(fp, rel)
            copy_bytes_safe(tmp_zip, cache_zip)
            print(f"  Cached HMG-API snapshot to: {cache_zip}")
        finally:
            if os.path.exists(tmp_zip):
                os.remove(tmp_zip)
    except OSError as exc:
        print(f"  Note: could not cache HMG-API snapshot ({exc}); continuing.")

    return target_dir


def author_uuid_from_pub_der(pub_der: bytes) -> str:
    d = bytearray(hashlib.sha256(pub_der).digest())
    d[6] = (d[6] & 15) | 128
    d[8] = (d[8] & 63) | 128
    h = d[:16].hex()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def derive_author_pub_der(priv_der: bytes) -> bytes:
    if shutil.which("openssl"):
        return subprocess.run(
            ["openssl", "pkey", "-inform", "DER", "-pubout", "-outform", "DER"],
            input=priv_der,
            capture_output=True,
            check=True,
        ).stdout
    from cryptography.hazmat.primitives import serialization

    priv_key = serialization.load_der_private_key(priv_der, password=None)
    return priv_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def generate_ecdsa_author_keypair(author_key_path: str, author_pub_path: str) -> bytes:
    """Generate a P-256 ECDSA keypair in HMG-API base64-DER format and return pub_der."""
    os.makedirs(os.path.dirname(author_key_path), exist_ok=True)
    if shutil.which("openssl"):
        priv_pem = subprocess.run(
            ["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1"],
            capture_output=True,
            check=True,
        ).stdout
        priv_der = subprocess.run(
            ["openssl", "pkey", "-outform", "DER"],
            input=priv_pem,
            capture_output=True,
            check=True,
        ).stdout
        pub_der = subprocess.run(
            ["openssl", "pkey", "-pubout", "-outform", "DER"],
            input=priv_pem,
            capture_output=True,
            check=True,
        ).stdout
    else:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        priv = ec.generate_private_key(ec.SECP256R1())
        priv_der = priv.private_bytes(
            serialization.Encoding.DER,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        pub_der = priv.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )

    with open(author_key_path, "w", encoding="ascii") as kf:
        kf.write(base64.b64encode(priv_der).decode("ascii") + "\n")
    if not sys.platform.startswith("win"):
        try:
            os.chmod(author_key_path, 0o600)
        except OSError:
            pass
    with open(author_pub_path, "w", encoding="ascii") as pf:
        pf.write(base64.b64encode(pub_der).decode("ascii") + "\n")
    return pub_der


def keystore_cert_sha256(keytool: str, keystore_path: str, storepass: str, alias: str) -> str:
    res = subprocess.run(
        [keytool, "-exportcert", "-keystore", keystore_path, "-storepass", storepass, "-alias", alias],
        capture_output=True,
        check=True,
    )
    return hashlib.sha256(res.stdout).hexdigest()


def ensure_keystore(keytool: str, keystore_path: str, storepass: str, alias: str, keypass: str) -> None:
    if os.path.isfile(keystore_path):
        return
    os.makedirs(os.path.dirname(keystore_path), exist_ok=True)
    subprocess.run(
        [
            keytool,
            "-genkeypair",
            "-keystore",
            keystore_path,
            "-storepass",
            storepass,
            "-keypass",
            keypass,
            "-alias",
            alias,
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "15000",
            "-dname",
            "CN=Fitcamx Gen5W",
        ],
        check=True,
    )


def _load_keys_from_dir(
    keys_dir: str,
    keytool: str,
    source_label: str,
    default_storepass: str = "fitcamx-app",
    default_alias: str = "fitcamx",
) -> SigningKeys | None:
    ks_path = os.path.join(keys_dir, "fitcamx-app.keystore")
    ak_path = os.path.join(keys_dir, "author.key")
    ap_path = os.path.join(keys_dir, "author.key.pub")

    if not (os.path.isfile(ks_path) and os.path.isfile(ak_path)):
        return None

    meta_path = os.path.join(keys_dir, "keystore.json")
    storepass = os.environ.get("FITCAMX_KEYSTORE_PASS") or default_storepass
    alias = os.environ.get("FITCAMX_KEY_ALIAS") or default_alias
    keypass = os.environ.get("FITCAMX_KEY_PASS") or storepass
    if os.path.isfile(meta_path):
        try:
            meta = json.loads(open(meta_path, "r", encoding="utf-8").read())
            storepass = os.environ.get("FITCAMX_KEYSTORE_PASS") or meta.get("storepass", storepass)
            alias = os.environ.get("FITCAMX_KEY_ALIAS") or meta.get("alias", alias)
            keypass = os.environ.get("FITCAMX_KEY_PASS") or meta.get("keypass", storepass)
        except (OSError, json.JSONDecodeError):
            pass

    priv_der = base64.b64decode("".join(open(ak_path, "r", encoding="utf-8").read().split()))
    if os.path.isfile(ap_path):
        pub_der = base64.b64decode("".join(open(ap_path, "r", encoding="utf-8").read().split()))
    else:
        pub_der = derive_author_pub_der(priv_der)
        write_bytes_safe(ap_path, (base64.b64encode(pub_der).decode("ascii") + "\n").encode("ascii"))

    uuid = author_uuid_from_pub_der(pub_der)
    cert_fp = keystore_cert_sha256(keytool, ks_path, storepass, alias)
    matches = uuid == EXPECTED_RELEASE_UUID and cert_fp == EXPECTED_APK_CERT_SHA256
    return SigningKeys(
        keystore_path=ks_path,
        keystore_pass=storepass,
        key_alias=alias,
        key_pass=keypass,
        author_key_path=ak_path,
        author_pub_path=ap_path,
        author_uuid=uuid,
        apk_cert_sha256=cert_fp,
        source=source_label,
        is_ephemeral=False,
        matches_release_identity=matches,
    )


def resolve_signing_keys(keytool: str, require_release: bool = False) -> SigningKeys:
    """Resolve signing keys across CI secrets, persistent cloud storage, local keys, or dev fallback."""
    # 1. Environment variables / GitHub Actions Secrets
    ks_b64 = os.environ.get("FITCAMX_KEYSTORE_BASE64", "").strip()
    ak_b64 = os.environ.get("FITCAMX_AUTHOR_KEY", "").strip()
    if ks_b64 and ak_b64:
        tmp_dir = tempfile.mkdtemp(prefix="fitcamx_ci_keys_")
        atexit.register(lambda d=tmp_dir: shutil.rmtree(d, ignore_errors=True))
        ks_bytes = base64.b64decode("".join(ks_b64.split()))
        priv_der = base64.b64decode("".join(ak_b64.split()))
        ap_b64 = os.environ.get("FITCAMX_AUTHOR_PUB", "").strip()
        pub_der = base64.b64decode("".join(ap_b64.split())) if ap_b64 else derive_author_pub_der(priv_der)

        ks_path = os.path.join(tmp_dir, "fitcamx-app.keystore")
        ak_path = os.path.join(tmp_dir, "author.key")
        ap_path = os.path.join(tmp_dir, "author.key.pub")
        with open(ks_path, "wb") as f:
            f.write(ks_bytes)
        with open(ak_path, "w", encoding="ascii") as f:
            f.write(base64.b64encode(priv_der).decode("ascii") + "\n")
        with open(ap_path, "w", encoding="ascii") as f:
            f.write(base64.b64encode(pub_der).decode("ascii") + "\n")

        storepass = os.environ.get("FITCAMX_KEYSTORE_PASS") or "fitcamx-app"
        alias = os.environ.get("FITCAMX_KEY_ALIAS") or "fitcamx"
        keypass = os.environ.get("FITCAMX_KEY_PASS") or storepass
        uuid = author_uuid_from_pub_der(pub_der)
        cert_fp = keystore_cert_sha256(keytool, ks_path, storepass, alias)
        matches = uuid == EXPECTED_RELEASE_UUID and cert_fp == EXPECTED_APK_CERT_SHA256
        return SigningKeys(
            keystore_path=ks_path,
            keystore_pass=storepass,
            key_alias=alias,
            key_pass=keypass,
            author_key_path=ak_path,
            author_pub_path=ap_path,
            author_uuid=uuid,
            apk_cert_sha256=cert_fp,
            source="environment secrets (FITCAMX_KEYSTORE_BASE64 + FITCAMX_AUTHOR_KEY)",
            is_ephemeral=False,
            matches_release_identity=matches,
        )

    # 2. Explicit FITCAMX_KEYS_DIR, persistent cloud keys (~/.gemini/fitcamx-gen5w/keys), or ../build/keys
    search_dirs = []
    if os.environ.get("FITCAMX_KEYS_DIR"):
        search_dirs.append((os.path.abspath(os.environ["FITCAMX_KEYS_DIR"]), "FITCAMX_KEYS_DIR"))
    search_dirs.append((get_persistent_keys_dir(), f"persistent cloud storage ({get_persistent_keys_dir()})"))
    search_dirs.append((os.path.abspath(os.path.join(BASE_DIR, "..", "build", "keys")), "local ../build/keys"))

    for kdir, label in search_dirs:
        loaded = _load_keys_from_dir(kdir, keytool, label)
        if loaded is not None:
            return loaded

    if require_release:
        sys.exit(
            "ERROR: Release signing keys are required (--require-release-keys), but none were found.\n"
            "  - In Antigravity Cloud: upload fitcamx-app.keystore & author.key and run:\n"
            "      python3 tools/manage_keys.py import\n"
            "  - In GitHub Actions: configure repository secrets FITCAMX_KEYSTORE_BASE64 and FITCAMX_AUTHOR_KEY\n"
            "      (or run `python3 tools/manage_keys.py import --sync-github-secrets`)."
        )

    # 3. Ephemeral dev keys fallback (for CI PRs and cloud testing before production keys are imported)
    dev_keys_dir = os.path.expanduser("~/.cache/fitcamx-dev-keys")
    os.makedirs(dev_keys_dir, exist_ok=True)
    ks_path = os.path.join(dev_keys_dir, "fitcamx-app.keystore")
    ak_path = os.path.join(dev_keys_dir, "author.key")
    ap_path = os.path.join(dev_keys_dir, "author.key.pub")
    ensure_keystore(keytool, ks_path, "fitcamx-app", "fitcamx", "fitcamx-app")
    if not (os.path.isfile(ak_path) and os.path.isfile(ap_path)):
        pub_der = generate_ecdsa_author_keypair(ak_path, ap_path)
    else:
        pub_der = base64.b64decode("".join(open(ap_path, "r", encoding="utf-8").read().split()))

    uuid = author_uuid_from_pub_der(pub_der)
    cert_fp = keystore_cert_sha256(keytool, ks_path, "fitcamx-app", "fitcamx")
    return SigningKeys(
        keystore_path=ks_path,
        keystore_pass="fitcamx-app",
        key_alias="fitcamx",
        key_pass="fitcamx-app",
        author_key_path=ak_path,
        author_pub_path=ap_path,
        author_uuid=uuid,
        apk_cert_sha256=cert_fp,
        source=f"ephemeral dev keys ({dev_keys_dir})",
        is_ephemeral=True,
        matches_release_identity=False,
    )
