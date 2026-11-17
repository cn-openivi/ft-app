#!/usr/bin/env python3
"""Owner-approved FT190 GitHub prerelease; no tokens or keystore bytes logged."""
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen
from urllib.error import HTTPError

repo = os.environ["GITHUB_REPOSITORY"]
head = os.environ["GITHUB_SHA"]
source = os.environ["SOURCE_SHA"]
tag = os.environ["RELEASE_TAG"]
apk = os.environ["APK_NAME"]
token = os.environ["GH_RELEASE_TOKEN"]
base = "https://api.github.com/repos/" + repo
headers = {
    "Authorization": "Bearer " + token,
    "Accept": "application/vnd.github+json",
    "User-Agent": "OpenIVI-FT190-Release",
    "X-GitHub-Api-Version": "2022-11-28",
}

def req(url, method="GET", data=None, mime=None, accept=None):
    h = headers.copy()
    if mime:
        h["Content-Type"] = mime
    if accept:
        h["Accept"] = accept
    r = Request(url, headers=h, data=data, method=method)
    try:
        with urlopen(r, timeout=180) as response:
            return response.read()
    except HTTPError as exc:
        if exc.code == 404:
            return None
        raise

def jreq(url, method="GET", payload=None):
    raw = req(url, method,
              json.dumps(payload, ensure_ascii=False).encode("utf-8")
              if payload is not None else None,
              "application/json" if payload is not None else None)
    return json.loads(raw.decode("utf-8")) if raw is not None else None

root = Path("verified")
names = [apk, "SHA256SUMS", "PROVENANCE.txt", "apk-badging.txt", "apksigner.txt"]
digest = hashlib.sha256((root / apk).read_bytes()).hexdigest()
if (root / "SHA256SUMS").read_text().split()[0] != digest:
    raise SystemExit("Owner APK checksum mismatch before release")
body = Path("docs/releases/FT190-owner-signed-20261117.md").read_text()
body += "\n\n## Контрольная сумма и подпись\n"
body += "- Исходный SHA: " + source + "\n"
body += "- Коммит сборки: " + head + "\n"
body += "- SHA-256 APK: " + digest + "\n"
body += "- Сертификат владельца SHA-256: " + os.environ["CERT_SHA"] + "\n"
body += "- Полевой статус: **на ГУ не проверено**.\n"
title = "OpenIVI FT190 — подписанная тестовая сборка XA60"
release = jreq(base + "/releases/tags/" + quote(tag, safe=""))
if release is None:
    release = jreq(base + "/releases", "POST", {
        "tag_name": tag,
        "target_commitish": head,
        "name": title,
        "body": body,
        "draft": True,
        "prerelease": True,
    })
else:
    if release.get("name") != title:
        raise SystemExit("Tag already belongs to another GitHub release")
    release = jreq(base + "/releases/" + str(release["id"]), "PATCH", {
        "name": title, "body": body, "draft": True, "prerelease": True,
    })
if not release:
    raise SystemExit("Cannot create release")
tag_readback = jreq(base + "/git/ref/tags/" + quote(tag, safe=""))
if not tag_readback or tag_readback["object"]["sha"] != head:
    raise SystemExit("Published tag points to wrong source")

assets = {x["name"]: x for x in release["assets"]}
upload = release["upload_url"].split("{")[0]
for name in names:
    raw = (root / name).read_bytes()
    sha = hashlib.sha256(raw).hexdigest()
    asset = assets.get(name)
    if asset is None:
        mime = ("application/vnd.android.package-archive"
                if name.endswith(".apk") else "text/plain")
        response = req(upload + "?" + urlencode({"name": name}), "POST", raw, mime)
        asset = json.loads(response.decode("utf-8"))
    if asset.get("size") != len(raw):
        raise SystemExit("GitHub asset length mismatch: " + name)
    if asset.get("digest") != "sha256:" + sha:
        raise SystemExit("GitHub asset server SHA mismatch: " + name)
    # Try the full authenticated bytes first; fall back only to an independent
    # GET of GitHub's server-calculated digest when the API gives JSON metadata.
    downloaded = req(base + "/releases/assets/" + str(asset["id"]),
                     accept="application/octet-stream")
    if downloaded is not None and hashlib.sha256(downloaded).hexdigest() == sha:
        print("FT190_GITHUB_BYTES_READBACK=PASS " + name)
    else:
        fresh = jreq(base + "/releases/assets/" + str(asset["id"]))
        if fresh is None or fresh.get("digest") != "sha256:" + sha:
            raise SystemExit("GitHub independent digest mismatch: " + name)
        print("FT190_GITHUB_DIGEST_READBACK=PASS " + name)

published = jreq(base + "/releases/" + str(release["id"]), "PATCH", {
    "name": title, "body": body, "draft": False, "prerelease": True,
})
fresh = jreq(base + "/releases/tags/" + quote(tag, safe=""))
if not published or not fresh or fresh.get("draft"):
    raise SystemExit("GitHub release was not published")
if apk not in [x["name"] for x in fresh["assets"]]:
    raise SystemExit("Signed APK absent from GitHub release")
print("FT190_GITHUB_RELEASE=" + fresh["html_url"])
print("FT190_GITHUB_APK_SHA256=" + digest)
print("FT190_GITHUB_RELEASE_READBACK=PASS")
