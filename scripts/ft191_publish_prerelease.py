#!/usr/bin/env python3
"""Publish FT191 owner-signed TEST APK as a GitHub PRE-RELEASE (not stable)."""
import hashlib
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

REPO = "cn-openivi/ft-app"
SIGNED_SHA = "29a2f9d7e33f1a410986f157739fd089568bde28"
SOURCE_SHA = "685f7988d812c6396d6592203146e3d2ad3e5f66"
ZIP_SHA = "ef417cb44a5e10c445e5cb1d3058246a224b82e281074eda78e1d1abd88d8afd"
APK_SHA = "72d78875125c7af6d050fef585a2d05c5d4cbca4d542867030a19c74f58e6ef8"
CERT_SHA = "e877ebac8f7938789b6fbac36248a866aeb4803370fe46ad9bd8f397a07f691c"
TAG = "xa60-ft191-29a2f9d7"
TITLE = "OpenIVI FT191 — исправления ICCOA, Android Auto и CarLife (тестовая сборка)"
APK_NAME = "FT191_XA60_OWNER_SIGNED.apk"
TOKEN = os.environ["GH_RELEASE_TOKEN"]
assert os.environ["GITHUB_REPOSITORY"] == REPO
base = "https://api.github.com/repos/" + REPO
headers = {
    "Authorization": "Bearer " + TOKEN,
    "Accept": "application/vnd.github+json",
    "User-Agent": "OpenIVI-FT191-Owner-PreRelease",
    "X-GitHub-Api-Version": "2022-11-28",
}

def req(url, method="GET", data=None, mime=None, accept=None):
    h = headers.copy()
    if mime: h["Content-Type"] = mime
    if accept: h["Accept"] = accept
    request = Request(url, data=data, headers=h, method=method)
    try:
        with urlopen(request, timeout=240) as result:
            return result.read()
    except HTTPError as exc:
        if method == "GET" and exc.code == 404:
            return None
        raise

def jreq(url, method="GET", data=None):
    payload = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
    result = req(url, method, payload, "application/json" if payload is not None else None)
    return json.loads(result.decode("utf-8")) if result is not None else None

work = Path("verified")
oldname = "FT191_XA60_OWNER_SIGNED_FIELD_CANDIDATE.apk"
apkdata = (work / oldname).read_bytes()
assert hashlib.sha256(apkdata).hexdigest() == APK_SHA
assert len(apkdata) == 41291541
provenance = (work / "PROVENANCE.txt").read_text()
badging = (work / "apk-badging.txt").read_text()
signer = (work / "apksigner.txt").read_text()
assert "CI_SHA=" + SIGNED_SHA in provenance
assert "SOURCE_SHA=" + SOURCE_SHA in provenance
assert "APP_ID=app.ft.xa60" in provenance
assert "VERSION_CODE=191" in provenance
assert "SIGNED=OWNER_CERT" in provenance
assert "FIELD=NOT_TESTED" in provenance
assert "CERT_SHA256=" + CERT_SHA in provenance
assert "package: name='app.ft.xa60' versionCode='191'" in badging
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signer
assert "Signer #1 certificate SHA-256 digest: " + CERT_SHA in signer
assert (work / "SHA256SUMS").read_text().split()[0] == APK_SHA
print("FT191_APK_SHA_SIGNER_AND_VERSION=PASS")

notes = Path("docs/releases/FT191-owner-signed-prerelease-20261119.md").read_text()
assert notes.startswith("# OpenIVI FT191") and "Pre-release" in notes
assert APK_SHA in notes
assert "Полевые испытания" in notes
# Preserve the binary signed with the existing owner certificate; do NOT rebuild.
upload_files = {
    APK_NAME: apkdata,
    "SHA256SUMS": (APK_SHA + "  " + APK_NAME + "\n").encode("utf-8"),
    "PROVENANCE.txt": provenance.encode("utf-8"),
}

# Reuse FT190's sanctioned GitHub publish flow: pinned tag, draft during
# binary transfer, independently verify assets, then publish as PRE-RELEASE.
tagpath = base + "/git/ref/tags/" + quote(TAG, safe="")
tagobj = jreq(tagpath)
if tagobj is None:
    tagobj = jreq(base + "/git/refs", "POST", {
        "ref": "refs/tags/" + TAG, "sha": SIGNED_SHA
    })
if tagobj["object"]["sha"] != SIGNED_SHA:
    raise SystemExit("FT191 tag SHA mismatch")

releases = jreq(base + "/releases?per_page=100") or []
release = next((v for v in releases if v.get("tag_name") == TAG), None)
if release is not None and release.get("name") != TITLE:
    raise SystemExit("Existing FT191 tag owned by different release")
if release is None:
    release = jreq(base + "/releases", "POST", {
        "tag_name": TAG, "target_commitish": SIGNED_SHA,
        "name": TITLE, "body": notes,
        "draft": True, "prerelease": True,
    })
elif release["draft"]:
    release = jreq(base + "/releases/" + str(release["id"]), "PATCH", {
        "name": TITLE, "body": notes, "draft": True, "prerelease": True
    })
else:
    if not release.get("prerelease") or release.get("body") != notes:
        raise SystemExit("Existing published release diverges from approved prerelease")
if release is None:
    raise SystemExit("Release draft not created")
if jreq(tagpath)["object"]["sha"] != SIGNED_SHA:
    raise SystemExit("FT191 tag changed")
upload = release["upload_url"].split("{")[0]
assets = {a["name"]: a for a in release.get("assets", [])}
for name, contents in upload_files.items():
    expected = hashlib.sha256(contents).hexdigest()
    a = assets.get(name)
    if a is None:
        mime = "application/vnd.android.package-archive" if name.endswith(".apk") else "text/plain"
        result = req(upload + "?" + urlencode({"name": name}), "POST", contents, mime)
        a = json.loads(result.decode("utf-8"))
    if a.get("size") != len(contents):
        raise SystemExit("Release asset size mismatch: " + name)
    freshasset = jreq(base + "/releases/assets/" + str(a["id"]))
    if freshasset is None or freshasset.get("size") != len(contents):
        raise SystemExit("Release asset independent metadata mismatch: " + name)
    if freshasset.get("digest") != "sha256:" + expected:
        # Do not accept an unverified binary when GitHub omits the server SHA.
        raise SystemExit("GitHub release asset digest mismatch: " + name)
    print("FT191_PRERELEASE_ASSET_SHA256_PASS " + name + " " + expected)

# Only PRE-RELEASE, never stable / latest. Assets are already present before publish.
release = jreq(base + "/releases/" + str(release["id"]), "PATCH", {
    "name": TITLE, "body": notes,
    "draft": False, "prerelease": True, "make_latest": "false",
})
fresh = jreq(base + "/releases/tags/" + quote(TAG, safe=""))
if fresh is None or fresh.get("draft") or not fresh.get("prerelease"):
    raise SystemExit("FT191 prerelease publication not confirmed")
if fresh.get("name") != TITLE or fresh.get("body") != notes:
    raise SystemExit("FT191 Russian description readback mismatch")
if fresh.get("make_latest") is True:
    raise SystemExit("FT191 incorrectly marked Latest")
if {a.get("name") for a in fresh.get("assets", [])} != set(upload_files):
    raise SystemExit("FT191 prerelease missing or unexpected assets")
for asset in fresh["assets"]:
    expected = hashlib.sha256(upload_files[asset["name"]]).hexdigest()
    if asset.get("digest") != "sha256:" + expected:
        raise SystemExit("Published FT191 asset digest differs: " + asset["name"])
print("FT191_PRERELEASE_URL=" + fresh["html_url"])
print("FT191_PRERELEASE_ID=" + str(fresh["id"]))
print("FT191_PRE_RELEASE_TRUE=PASS")
print("FT191_STABLE_RELEASE_FALSE=PASS")
print("FT191_PRERELEASE_RUSSIAN_NOTES_AND_APK_READBACK=PASS")
