#!/usr/bin/env python3
"""Push the After Duty store listing into App Store Connect.

Reads docs/store-metadata/afterduty-listing.json -- the single source of truth --
so the listing that goes live cannot drift from the doc.

The ASC API cannot CREATE an app: the API itself reports
    The resource 'apps' does not allow 'CREATE'.
    Allowed operations are: GET_COLLECTION, GET_INSTANCE, UPDATE
so the app record must already exist, made in the App Store Connect UI on the
pre-registered bundle id com.afterduty.app. This script fills it in.

Credentials come from an App Store Connect API key. Override the defaults with
ASC_ISSUER / ASC_KEY_ID / ASC_KEY_PATH if the key ever rotates.

Usage:
    python3 tools/push_ios_listing.py --dry-run
    python3 tools/push_ios_listing.py
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

import jwt

REPO = pathlib.Path(__file__).resolve().parent.parent
META = REPO / "docs/store-metadata/afterduty-listing.json"
BASE = "https://api.appstoreconnect.apple.com"

ISSUER = os.environ.get("ASC_ISSUER", "38d92e7e-4418-4b92-a12d-602b2ba8fc00")
KEY_ID = os.environ.get("ASC_KEY_ID", "VPY2K9JP7D")
KEY_PATH = pathlib.Path(os.environ.get(
    "ASC_KEY_PATH",
    str(pathlib.Path.home() / ".appstoreconnect/private_keys/AuthKey_VPY2K9JP7D.p8")))


def token() -> str:
    now = int(time.time())
    return jwt.encode(
        {"iss": ISSUER, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        KEY_PATH.read_text(), algorithm="ES256",
        headers={"kid": KEY_ID, "typ": "JWT"})


def call(method: str, path: str, body=None):
    req = urllib.request.Request(
        path if path.startswith("http") else BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method)
    req.add_header("Authorization", f"Bearer {token()}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except Exception:
            return e.code, {"raw": raw.decode(errors="ignore")[:2000]}


def find_app(bundle_id: str):
    st, out = call("GET", f"/v1/apps?filter[bundleId]={bundle_id}&fields[apps]=name,bundleId")
    if st != 200:
        return None, f"lookup failed ({st}): {json.dumps(out)[:300]}"
    apps = out.get("data") or []
    if not apps:
        return None, (f"no app record for {bundle_id} yet -- create it in the App Store "
                      f"Connect UI (My Apps -> + -> New App), then re-run.")
    return apps[0]["id"], None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    meta = json.loads(META.read_text())
    ident = meta["identity"]
    urls = meta["urls"]
    description = meta["descriptionBody"] + meta["iosDescriptionSuffix"]

    # Apple's limits -- fail here rather than on an opaque 409 from the API.
    for label, value, limit in (
        ("name", ident["appName"], 30),
        ("subtitle", ident["subtitle"], 30),
        ("keywords", meta["keywords"], 100),
        ("description", description, 4000),
    ):
        if len(value) > limit:
            print(f"ERROR: {label} is {len(value)} chars, limit {limit}")
            return 1

    if args.dry_run:
        print(json.dumps({
            "bundleId": ident["bundleId"],
            "name": ident["appName"],
            "subtitle": ident["subtitle"],
            "privacyPolicyUrl": urls["privacyPolicy"],
            "keywords": meta["keywords"],
            "supportUrl": urls["support"],
            "marketingUrl": urls["marketing"],
            "description": description,
        }, indent=2))
        return 0

    app_id, err = find_app(ident["bundleId"])
    if err:
        print(f"ERROR: {err}")
        return 1
    print(f"app {app_id} ({ident['bundleId']})")

    failures = []

    # App-level: name, subtitle, privacy policy URL.
    st, infos = call("GET", f"/v1/apps/{app_id}/appInfos")
    for info in (infos.get("data") or []):
        st, locs = call("GET", f"/v1/appInfos/{info['id']}/appInfoLocalizations")
        for loc in (locs.get("data") or []):
            if loc["attributes"]["locale"] != ident["primaryLocale"]:
                continue
            st, out = call("PATCH", f"/v1/appInfoLocalizations/{loc['id']}", {
                "data": {"type": "appInfoLocalizations", "id": loc["id"], "attributes": {
                    "name": ident["appName"],
                    "subtitle": ident["subtitle"],
                    "privacyPolicyUrl": urls["privacyPolicy"],
                }}})
            print(f"  appInfoLocalization {loc['id']}: {st}")
            if st != 200:
                failures.append(json.dumps(out)[:300])

    # Version-level: description, keywords, support/marketing URLs.
    st, vers = call("GET", f"/v1/apps/{app_id}/appStoreVersions?limit=1"
                           f"&fields[appStoreVersions]=versionString,appStoreState")
    versions = vers.get("data") or []
    if not versions:
        print("  no appStoreVersion yet -- create the first version in ASC, then re-run "
              "to push description/keywords.")
    for v in versions:
        st, locs = call("GET", f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations")
        for loc in (locs.get("data") or []):
            if loc["attributes"]["locale"] != ident["primaryLocale"]:
                continue
            st, out = call("PATCH", f"/v1/appStoreVersionLocalizations/{loc['id']}", {
                "data": {"type": "appStoreVersionLocalizations", "id": loc["id"], "attributes": {
                    "description": description,
                    "keywords": meta["keywords"],
                    "supportUrl": urls["support"],
                    "marketingUrl": urls["marketing"],
                }}})
            print(f"  versionLocalization {loc['id']}: {st}")
            if st != 200:
                failures.append(json.dumps(out)[:300])

    for f in failures:
        print(f"  FAILED: {f}")
    if failures:
        return 1

    print("Listing copy pushed. Still owner/console work: screenshots, App Privacy "
          "answers, subscription group + pro_monthly/pro_annual pricing, and the "
          "reviewer notes from docs/store-metadata/afterduty-listing.json.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
