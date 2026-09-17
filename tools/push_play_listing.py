#!/usr/bin/env python3
"""Push the After Duty store listing into Google Play.

Reads docs/store-metadata/afterduty-listing.json -- the single source of truth --
so the listing that goes live cannot drift from the doc.

The Play Developer API cannot CREATE an app (there is no applications.create
method; only edits/tracks/subscriptions/users can be created). The app record
must already exist, made in the Play Console UI. This script fills it in.

Usage:
    python3 tools/push_play_listing.py --dry-run    # show what would change
    python3 tools/push_play_listing.py              # apply and commit the edit
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

REPO = pathlib.Path(__file__).resolve().parent.parent
META = REPO / "docs/store-metadata/afterduty-listing.json"
SA_PATH = pathlib.Path.home() / ".gcp/play-publisher.json"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3"

_tok: dict = {"v": None, "exp": 0.0}


def token() -> str:
    if _tok["v"] and time.time() < _tok["exp"] - 60:
        return _tok["v"]
    sa = json.loads(SA_PATH.read_text())
    now = int(time.time())
    assertion = jwt.encode(
        {"iss": sa["client_email"], "scope": SCOPE, "aud": sa["token_uri"],
         "iat": now, "exp": now + 3600},
        sa["private_key"], algorithm="RS256")
    data = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion}).encode()
    with urllib.request.urlopen(urllib.request.Request(sa["token_uri"], data=data)) as r:
        body = json.loads(r.read())
    _tok["v"] = body["access_token"]
    _tok["exp"] = time.time() + body.get("expires_in", 3600)
    return _tok["v"]


def call(method: str, path: str, body=None):
    req = urllib.request.Request(
        BASE + path,
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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    meta = json.loads(META.read_text())
    pkg = meta["identity"]["packageName"]
    locale = meta["identity"]["primaryLocale"]

    full_description = meta["descriptionBody"] + meta["playDescriptionSuffix"]
    listing = {
        "language": locale,
        "title": meta["identity"]["appName"],
        "shortDescription": meta["identity"]["shortDescription"],
        "fullDescription": full_description,
    }
    details = {
        "defaultLanguage": locale,
        "contactWebsite": meta["urls"]["contactWebsite"],
        "contactEmail": meta["urls"]["contactEmail"],
    }

    # Play's own limits -- catch an overrun here rather than as an opaque 400.
    for field, limit in (("title", 30), ("shortDescription", 80), ("fullDescription", 4000)):
        if len(listing[field]) > limit:
            print(f"ERROR: {field} is {len(listing[field])} chars, limit {limit}")
            return 1

    if args.dry_run:
        print(f"package: {pkg}")
        print(json.dumps({"details": details, "listing": listing}, indent=2))
        return 0

    st, edit = call("POST", f"/applications/{pkg}/edits", {})
    if st != 200:
        print(f"ERROR opening edit ({st}): {json.dumps(edit)[:400]}")
        print("If this is 404, the app record does not exist yet -- create it in the "
              "Play Console UI first. If 403, grant the play-publisher service account "
              "access to this app under Users and permissions.")
        return 1
    eid = edit["id"]
    print(f"edit {eid}")

    ok = True
    for method, path, body, label in (
        ("PUT", f"/applications/{pkg}/edits/{eid}/details", details, "details"),
        ("PUT", f"/applications/{pkg}/edits/{eid}/listings/{locale}", listing, "listing"),
    ):
        st, out = call(method, path, body)
        print(f"  {label}: {st}")
        if st != 200:
            print(f"    {json.dumps(out)[:400]}")
            ok = False

    if not ok:
        call("DELETE", f"/applications/{pkg}/edits/{eid}")
        print("rolled back -- edit deleted, nothing committed")
        return 1

    st, out = call("POST", f"/applications/{pkg}/edits/{eid}:commit")
    print(f"commit: {st}")
    if st != 200:
        print(json.dumps(out)[:400])
        return 1

    print("Listing live. Console-only forms still need the owner: Data safety, "
          "Content rating (IARC), App access, Target audience -- drafted in "
          "docs/playstore/google-play-listing.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
