#!/usr/bin/env python3
"""
M0: provision the whole workspace-local Android toolchain. User-space only.

Why a custom installer instead of sdkmanager/bootstrap:
  * no root, $HOME not writable  -> everything extracted under .toolchain/
  * this box resolves dl.google.com in 0.3s or 10s+ (flaky), while the CDN IP is
    stable -> we download through `curl --resolve` and skip DNS entirely
  * bandwidth ceiling is ~300 KB/s, so every download must be resumable and the
    order must follow the critical path (build tools first, image last)

Idempotent: already-installed components are skipped.
Usage:  python3 scripts/provision.py [stage ...]     (default: all)
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TC = os.path.join(ROOT, ".toolchain")
DL = os.path.join(TC, "downloads")
REPO = os.path.join(TC, "repo")
SDK = os.path.join(TC, "android-sdk")
TMP = os.path.join(TC, "tmp")

GOOGLE_HOST = "dl.google.com"
# observed stable CDN edges for this network; probed at runtime
GOOGLE_IP_CANDIDATES = ["114.250.70.33", "114.250.69.33"]

JDK_DIR = os.path.join(TC, "jdk17")
GRADLE_DIR = os.path.join(TC, "gradle-8.9")

JDK_URL = ("https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/"
           "OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz")
GRADLE_URL = "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip"
CMDLINE_URL = f"https://{GOOGLE_HOST}/android/repository/commandlinetools-linux-11076708_latest.zip"

# key -> (local xml filename, xml url, base url for the relative archive URLs it
# contains). The base URL matters: archive URLs inside a repository XML are
# relative to that XML's own directory, so a system-image archive must be fetched
# from `.../repository/sys-img/<tag>/` and not from `.../repository/`.
REPO_XML = {
    "repo": ("repository2-3.xml",
             f"https://{GOOGLE_HOST}/android/repository/repository2-3.xml",
             f"https://{GOOGLE_HOST}/android/repository/"),
    "sysimg-default": ("sys-img-default.xml",
                       f"https://{GOOGLE_HOST}/android/repository/sys-img/android/sys-img2-3.xml",
                       f"https://{GOOGLE_HOST}/android/repository/sys-img/android/"),
    "sysimg-google_apis_playstore": (
        "sys-img-google_apis_playstore.xml",
        f"https://{GOOGLE_HOST}/android/repository/sys-img/google_apis_playstore/sys-img2-3.xml",
        f"https://{GOOGLE_HOST}/android/repository/sys-img/google_apis_playstore/"),
}

# package path -> (repo key, install dir relative to SDK)
SDK_PACKAGES = [
    ("platform-tools",        "repo", "platform-tools"),
    ("platforms;android-35",  "repo", "platforms/android-35"),
    ("build-tools;35.0.0",    "repo", "build-tools/35.0.0"),
    ("emulator",              "repo", "emulator"),
    ("system-images;android-35;default;x86_64", "sysimg-default",
     "system-images/android-35/default/x86_64"),
]

LICENSE_HASHES = [
    "24333f8a63b6825ea9c5514f83c2829b004d1fee",
    "8933bad161af4178b1185d1a37fbf41ea5269c55",
    "d56f5187479451eabf01fb78af6dfcb131a6481e",
]

_google_edge_ranking = None

# Optional full mirror of https://dl.google.com/android/repository/ (China-friendly
# alternative when the Google CDN is slow or unreachable).
SDK_MIRROR = os.environ.get("SDK_MIRROR", "").rstrip("/") + "/" if os.environ.get("SDK_MIRROR") else ""
GOOGLE_REPO_PREFIX = f"https://{GOOGLE_HOST}/android/repository/"


def apply_mirror(url):
    if SDK_MIRROR and url.startswith(GOOGLE_REPO_PREFIX):
        return SDK_MIRROR + url[len(GOOGLE_REPO_PREFIX):]
    return url


def log(msg):
    print(f"\033[1;36m==> {msg}\033[0m", flush=True)


def warn(msg):
    print(f"\033[1;33m!!! {msg}\033[0m", flush=True)


def mkdirs():
    for d in (TC, DL, REPO, SDK, TMP):
        os.makedirs(d, exist_ok=True)


def base_curl():
    # NOTE: this box ships curl 7.61 (CentOS 8), so --retry-all-errors (7.71+) and
    # --no-progress-meter (7.67+) are unavailable. --retry-connrefused (7.52+) is fine.
    return ["curl", "-fL", "--retry", "5", "--retry-delay", "3", "--retry-connrefused",
            "--connect-timeout", "20", "-sS"]


def _edge_speed(ip, url, sample_bytes=4 * 1024 * 1024, seconds=8):
    """
    Throughput of one CDN edge for `url`, in bytes/s (0 on failure).

    Measured, not merely probed for reachability: the Google China edges differ by
    more than 40x from each other on this link (one measured 13 KB/s while another
    did 628 KB/s), and a reachability-only probe happily picks the slow one and then
    stalls for tens of minutes.
    """
    cmd = base_curl() + [
        "--resolve", f"{GOOGLE_HOST}:443:{ip}",
        "--max-time", str(seconds),
        "-r", f"0-{sample_bytes}",
        "-o", os.devnull,
        "-w", "%{speed_download}",
        url,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    try:
        return float(r.stdout.strip() or 0.0)
    except ValueError:
        return 0.0


def rank_google_edges(url=None):
    """Candidate dl.google.com edges, fastest first."""
    global _google_edge_ranking
    if _google_edge_ranking is not None and url is None:
        return _google_edge_ranking

    candidates = list(GOOGLE_IP_CANDIDATES)
    # DNS-resolved address as an extra candidate (slow lookup, so only once).
    try:
        resolved = subprocess.run(["getent", "hosts", GOOGLE_HOST],
                                  capture_output=True, text=True, timeout=15)
        for line in resolved.stdout.splitlines():
            parts = line.split()
            if parts and parts[0] not in candidates:
                candidates.append(parts[0])
    except Exception:
        pass

    probe_url = url or f"https://{GOOGLE_HOST}/android/repository/commandlinetools-linux-11076708_latest.zip"
    ranked = []
    for ip in candidates:
        speed = _edge_speed(ip, probe_url)
        print(f"    edge {ip}: {speed/1024:.0f} KB/s", flush=True)
        if speed > 0:
            ranked.append((speed, ip))
    ranked.sort(reverse=True)

    if not ranked:
        # Inconclusive probing (the CDN throttles bursts of range requests) must not
        # be read as "no edge works": real downloads from these edges still succeed,
        # so keep them as candidates and merely lose the speed ordering.
        warn("edge probing inconclusive; keeping all edges as candidates")
        _google_edge_ranking = [(ip, 0.0) for ip in candidates]
        return _google_edge_ranking

    _google_edge_ranking = [(ip, speed) for speed, ip in ranked]
    best_ip, best_speed = _google_edge_ranking[0]
    log(f"using dl.google.com edge {best_ip} at {best_speed/1024:.0f} KB/s (DNS bypass)")
    return _google_edge_ranking


def curl_get(url, dest, label=None):
    """
    Resumable download.

    For Google hosts this walks the ranked edge list (and finally plain DNS), so a
    single degraded edge cannot stall the whole provisioning run.

    Set SDK_MIRROR to a full mirror of `.../android/repository/` to skip Google
    entirely, e.g.:
        SDK_MIRROR=https://mirrors.cloud.tencent.com/AndroidSDK/ python3 scripts/provision.py
    """
    url = apply_mirror(url)
    label = label or os.path.basename(dest)
    host = urllib.parse.urlparse(url).netloc
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"    cached: {label} ({os.path.getsize(dest)/1048576:.0f} MB)", flush=True)
        return dest

    part = dest + ".part"
    attempts = []
    if host == GOOGLE_HOST:
        attempts = [["--resolve", f"{GOOGLE_HOST}:443:{ip}"]
                    for ip, _ in rank_google_edges(url)]
    attempts.append([])  # last resort: system DNS

    t0 = time.time()
    for index, extra in enumerate(attempts):
        started = os.path.getsize(part) if os.path.exists(part) else 0
        cmd = base_curl() + extra + [
            # Abort a transfer that collapses to a crawl so --retry can restart it
            # instead of hanging for tens of minutes.
            "--speed-limit", "30000", "--speed-time", "60",
            "-C", "-", "-o", part, url,
        ]
        r = subprocess.run(cmd)
        if r.returncode == 0 and os.path.exists(part):
            size = os.path.getsize(part)
            dt = max(time.time() - t0, 0.01)
            os.replace(part, dest)
            print(f"    {label}: {size/1048576:.0f} MB in {dt/60:.1f} min "
                  f"({size/1024/dt:.0f} KB/s)", flush=True)
            return dest
        resumed = os.path.getsize(part) if os.path.exists(part) else 0
        warn(f"{label}: attempt {index + 1}/{len(attempts)} failed "
             f"({started/1048576:.0f} -> {resumed/1048576:.0f} MB); trying next route")

    raise RuntimeError(f"download failed: {url}")


def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_single(src_zip, dest_dir, label):
    """Extract an SDK archive and normalise its layout to dest_dir."""
    if os.path.isdir(dest_dir) and os.listdir(dest_dir):
        print(f"    already extracted: {label}", flush=True)
        return
    work = os.path.join(TMP, re.sub(r"[^A-Za-z0-9_.-]", "_", label))
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work, exist_ok=True)
    subprocess.run(["unzip", "-q", "-o", src_zip, "-d", work], check=True)
    entries = [e for e in os.listdir(work) if not e.startswith(".")]
    wanted = os.path.basename(dest_dir.rstrip("/"))
    if wanted in entries:
        chosen = os.path.join(work, wanted)
    elif len(entries) == 1:
        chosen = os.path.join(work, entries[0])
    else:
        raise RuntimeError(f"cannot normalise {label}: entries={entries}")
    os.makedirs(os.path.dirname(dest_dir.rstrip("/")), exist_ok=True)
    shutil.rmtree(dest_dir, ignore_errors=True)
    shutil.move(chosen, dest_dir)
    shutil.rmtree(work, ignore_errors=True)
    print(f"    extracted -> {os.path.relpath(dest_dir, ROOT)}", flush=True)


# ---------------------------------------------------------------- stages

def stage_jdk():
    log("JDK 17 (Temurin, TUNA mirror)")
    if os.path.isfile(os.path.join(JDK_DIR, "bin", "javac")):
        print("    already installed"); return
    tgz = curl_get(JDK_URL, os.path.join(DL, "jdk17.tar.gz"), "jdk17.tar.gz")
    work = os.path.join(TMP, "jdk17")
    shutil.rmtree(work, ignore_errors=True); os.makedirs(work, exist_ok=True)
    subprocess.run(["tar", "-xzf", tgz, "-C", work, "--strip-components=1"], check=True)
    shutil.rmtree(JDK_DIR, ignore_errors=True)
    shutil.move(work, JDK_DIR)
    out = subprocess.run([os.path.join(JDK_DIR, "bin", "java"), "-version"],
                         capture_output=True, text=True)
    print("    " + (out.stderr or out.stdout).splitlines()[0], flush=True)


def stage_gradle():
    log("Gradle 8.9 (Tencent mirror)")
    if os.path.isfile(os.path.join(GRADLE_DIR, "bin", "gradle")):
        print("    already installed"); return
    z = curl_get(GRADLE_URL, os.path.join(DL, "gradle-8.9-bin.zip"), "gradle-8.9-bin.zip")
    extract_single(z, GRADLE_DIR, "gradle-8.9")


def repo_xml(key):
    fname, url, _base = REPO_XML[key]
    path = os.path.join(REPO, fname)
    if not os.path.exists(path):
        curl_get(url, path, fname)
    return ET.parse(path).getroot()


def repo_base(key):
    return REPO_XML[key][2]


def pick_archive(pkg_el, base_url):
    """Return (url, size, sha1) of the best linux/x64 archive for a package."""
    best = None
    for arch in pkg_el.iter("archive"):
        ho = arch.findtext("host-os")
        ha = arch.findtext("host-arch")
        if ho and ho != "linux":
            continue
        if ha and ha not in ("x64", "x86_64"):
            continue
        complete = arch.find("complete")
        if complete is None or not complete.findtext("url"):
            continue
        url = complete.findtext("url")
        if not url.startswith("http"):
            url = base_url + url
        chk = complete.find("checksum")
        best = (url, int(complete.findtext("size") or 0),
                chk.text if chk is not None else None)
    return best


def _rev_key(pkg_el):
    rev = pkg_el.find("revision")
    if rev is None:
        return ()
    vals = []
    for tag in ("major", "minor", "micro"):
        el = rev.find(tag)
        try:
            vals.append(int(el.text))
        except (AttributeError, TypeError, ValueError):
            vals.append(0)
    return tuple(vals)


def stage_sdk_packages():
    log("Android SDK packages (platform-tools / platform-35 / build-tools-35 / emulator / system-image)")
    total = 0
    for pkg_path, repo_key, dest_rel in SDK_PACKAGES:
        dest = os.path.join(SDK, dest_rel)
        root = repo_xml(repo_key)
        # a package path can appear several times with different revisions
        # (emulator does); always take the newest one.
        candidates = [p for p in root.iter("remotePackage") if p.get("path") == pkg_path]
        if not candidates:
            warn(f"{pkg_path} not found in {repo_key}; skipping")
            continue
        el = max(candidates, key=_rev_key)
        info = pick_archive(el, repo_base(repo_key))
        if not info:
            warn(f"{pkg_path}: no linux/x64 archive; skipping")
            continue
        url, size, chk = info
        total += size
        print(f"  - {pkg_path}  ({size/1048576:.0f} MB)", flush=True)
        if os.path.isdir(dest) and os.listdir(dest) and os.path.isfile(os.path.join(dest, "source.properties")):
            print("    already installed", flush=True)
            continue
        zpath = os.path.join(DL, os.path.basename(url))
        curl_get(url, zpath, os.path.basename(url))
        if chk:
            got = sha1(zpath)
            if got != chk:
                os.remove(zpath)
                raise RuntimeError(f"sha1 mismatch for {url}: {got} != {chk}")
            print("    sha1 ok", flush=True)
        extract_single(zpath, dest, pkg_path)
        os.remove(zpath)  # reclaim disk; the .toolchain already holds the extracted copy
    print(f"    total package payload: {total/1048576:.0f} MB", flush=True)

    # Record which system image is installed for scripts/emulator.sh, which needs the
    # exact package path when creating the AVD.
    for pkg_path, _repo_key, dest_rel in SDK_PACKAGES:
        if pkg_path.startswith("system-images;") and os.path.isdir(os.path.join(SDK, dest_rel)):
            with open(os.path.join(TC, "system-image.env"), "w") as f:
                f.write(f"IMAGE={pkg_path}\n")
            print(f"    system image recorded: {pkg_path}", flush=True)


def stage_licenses():
    log("SDK licenses (so AGP stops complaining)")
    ldir = os.path.join(SDK, "licenses")
    os.makedirs(ldir, exist_ok=True)
    for name in ("android-sdk-license", "android-sdk-preview-license"):
        with open(os.path.join(ldir, name), "w") as f:
            f.write("\n".join(LICENSE_HASHES) + "\n")
    print(f"    wrote {ldir}")

    # avdmanager/sdkmanager expect this file to exist in the user home.
    user_home = os.environ.get("ANDROID_USER_HOME", os.path.join(TC, "android-home"))
    os.makedirs(user_home, exist_ok=True)
    cfg = os.path.join(user_home, "repositories.cfg")
    if not os.path.exists(cfg):
        open(cfg, "w").close()
        print(f"    wrote {cfg}")


# Namespaces the repository parser expects when it reads a local package.xml.
PKG_XML_HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<ns2:repository
    xmlns:ns2="http://schemas.android.com/repository/android/common/02"
    xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03"
    xmlns:sdk-common="http://schemas.android.com/sdk/android/repo/common/03"
    xmlns:generic="http://schemas.android.com/repository/android/generic/02"
    xmlns:sys-img="http://schemas.android.com/sdk/android/repo/sys-img2/03"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
"""


def _local_name(tag):
    return tag.split("}", 1)[1] if "}" in tag else tag


def write_local_package_xml(pkg_path, repo_key, dest_dir):
    """
    Write the `package.xml` that Android tooling expects inside an installed SDK
    component.

    Extracting an official zip is not enough: without this file the SDK handler
    cannot see the component, so AGP falls back to fetching the *remote* package
    manifests — which this network blocks outright (connection reset), leaving the
    build hanging on "Still waiting for package manifests to be fetched remotely".

    The element body is copied verbatim from Google's own repository XML, so the
    type-details (xsi:type, api-level, ...) are exactly what the parser expects.
    """
    root = repo_xml(repo_key)
    candidates = [
        p for p in root.iter()
        if _local_name(p.tag) == "remotePackage" and p.get("path") == pkg_path
    ]
    if not candidates:
        warn(f"{pkg_path}: not in {repo_key}, cannot write package.xml")
        return False

    element = max(candidates, key=_rev_key)
    if not any(_local_name(c.tag) == "revision" for c in element):
        warn(f"{pkg_path}: no <revision>, refusing to write package.xml")
        return False

    body = [
        ET.tostring(child, encoding="unicode").strip()
        for child in element
        # `archives` are meaningless for a local install; `uses-license` would make
        # the parser warn ("Undefined ID android-sdk-license") because a local
        # package.xml carries no <license> block — the sdk/licenses/ files already
        # record acceptance.
        if _local_name(child.tag) not in ("archives", "channelRef", "uses-license")
    ]

    xml = (
        PKG_XML_HEADER
        + f'  <localPackage path="{pkg_path}" obsolete="false">\n'
        + "".join(f"    {chunk}\n" for chunk in body)
        + "  </localPackage>\n</ns2:repository>\n"
    )
    os.makedirs(dest_dir, exist_ok=True)
    with open(os.path.join(dest_dir, "package.xml"), "w") as f:
        f.write(xml)
    return True


def stage_package_xml():
    log("SDK component metadata (package.xml) - required for offline SDK resolution")
    written = 0
    for pkg_path, repo_key, dest_rel in SDK_PACKAGES:
        dest = os.path.join(SDK, dest_rel)
        if not os.path.isdir(dest) or not os.listdir(dest):
            warn(f"{pkg_path}: not installed yet, skipping")
            continue
        if write_local_package_xml(pkg_path, repo_key, dest):
            written += 1
            print(f"    wrote {os.path.relpath(dest, ROOT)}/package.xml", flush=True)
    print(f"    {written} component manifest(s) written", flush=True)


    print(f"    {written} component manifest(s) written", flush=True)


def stage_cmdline_tools():
    log("Android command-line tools (sdkmanager / avdmanager / apkanalyzer)")
    dest = os.path.join(SDK, "cmdline-tools", "latest")
    if os.path.isfile(os.path.join(dest, "bin", "sdkmanager")):
        print("    already installed"); return
    z = curl_get(CMDLINE_URL, os.path.join(DL, "commandlinetools-linux.zip"), "commandlinetools-linux.zip")
    work = os.path.join(TMP, "cmdtools")
    shutil.rmtree(work, ignore_errors=True); os.makedirs(work, exist_ok=True)
    subprocess.run(["unzip", "-q", "-o", z, "-d", work], check=True)
    src = os.path.join(work, "cmdline-tools")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.rmtree(dest, ignore_errors=True)
    shutil.move(src, dest)
    shutil.rmtree(work, ignore_errors=True)
    print(f"    extracted -> {os.path.relpath(dest, ROOT)}", flush=True)


STAGES = [
    ("jdk", stage_jdk),
    ("gradle", stage_gradle),
    ("sdk", stage_sdk_packages),
    ("pkgxml", stage_package_xml),
    ("licenses", stage_licenses),
    ("cmdtools", stage_cmdline_tools),
]


def main():
    mkdirs()
    wanted = sys.argv[1:] or [n for n, _ in STAGES]
    for name, fn in STAGES:
        if name in wanted:
            fn()
    log("provision complete")


if __name__ == "__main__":
    main()
