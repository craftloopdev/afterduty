#!/usr/bin/env python3
"""Ensure the App-Review demo account exists (idempotent).

The demo identity is a Firebase TEST phone number configured project-side in
Identity Platform (`signIn.phoneNumber.testPhoneNumbers`): a fixed number with a
fixed OTP that survives account deletion and never sends a real SMS. This script:

  1. Looks up the Firebase user for the test number; creates it if absent.
  2. Calls the production API as that user so the backend `users` row (and
     synthetic <uid>@firebase.local email) exists end-to-end.

The number and its code are credentials — anyone holding them can sign in to the
demo account on production. They are deliberately NOT stored in this repository;
supply them from the environment. Read the current pair from the Identity
Platform config, or from the App Store Connect review notes.

Safe to run any time — including right after testing DELETE /api/auth/account,
though even that is optional: the app auto-creates a fresh account on the
next test-number sign-in. Requires:
  DEMO_PHONE  E.164 test phone number, e.g. +1XXXXXXXXXX  (required)
  DEMO_OTP    its fixed verification code                 (optional, print only)
  GOOGLE_APPLICATION_CREDENTIALS (default ~/.gcp/firebase-adminsdk.json)
  FIREBASE_API_KEY (web API key; default read from ci_post_clone.sh)

Usage:  DEMO_PHONE=+1XXXXXXXXXX python3 tests/create_demo_account.py
"""
import json
import os
import re
import subprocess
import sys
import urllib.request

DEMO_PHONE = os.environ.get("DEMO_PHONE", "")
DEMO_OTP = os.environ.get("DEMO_OTP", "")
PROJECT = "craftloop-va-claim"
API = "https://va-claim-api-1048958573080.us-central1.run.app"
ADMIN_KEY = os.environ.get(
    "GOOGLE_APPLICATION_CREDENTIALS",
    os.path.expanduser("~/.gcp/firebase-adminsdk.json"))


def admin_token() -> str:
    out = subprocess.run(
        ["gcloud", "auth", "application-default", "print-access-token"],
        env={**os.environ, "GOOGLE_APPLICATION_CREDENTIALS": ADMIN_KEY},
        capture_output=True, text=True, check=True)
    return out.stdout.strip()


def api_key() -> str:
    if os.environ.get("FIREBASE_API_KEY"):
        return os.environ["FIREBASE_API_KEY"]
    src = open(os.path.join(os.path.dirname(__file__), "..",
                            "web/ios/App/ci_scripts/ci_post_clone.sh")).read()
    return re.search(r"AIzaSy[A-Za-z0-9_-]+", src).group(0)


def call(url, body, bearer):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {bearer}",
                 "Content-Type": "application/json"},
        method="POST" if body is not None else "GET")
    return json.load(urllib.request.urlopen(req))


def main() -> None:
    if not re.fullmatch(r"\+[1-9]\d{7,14}", DEMO_PHONE):
        sys.exit("DEMO_PHONE must be set to the E.164 test number, e.g. "
                 "DEMO_PHONE=+1XXXXXXXXXX python3 tests/create_demo_account.py")
    tok = admin_token()
    base = f"https://identitytoolkit.googleapis.com/v1/projects/{PROJECT}"

    found = call(f"{base}/accounts:lookup", {"phoneNumber": [DEMO_PHONE]}, tok)
    users = found.get("users") or []
    if users:
        uid = users[0]["localId"]
        print(f"firebase user exists: {uid}")
    else:
        created = call(f"{base}/accounts", {"phoneNumber": DEMO_PHONE}, tok)
        uid = created["localId"]
        print(f"firebase user created: {uid}")

    # Backend row: authenticate as the demo user against prod /auth/me
    # (mint_token.py mints an ID token for an arbitrary uid via signJwt).
    mint_env = {**os.environ,
                "GOOGLE_APPLICATION_CREDENTIALS": ADMIN_KEY,
                "FIREBASE_PROJECT_ID": PROJECT,
                "FIREBASE_API_KEY": api_key(),
                "FIREBASE_TEST_UID": uid,
                "FIREBASE_TEST_EMAIL": f"{uid}@firebase.local"}
    out = subprocess.run(
        [sys.executable, os.path.join(os.path.dirname(__file__), "mint_token.py")],
        env=mint_env, capture_output=True, text=True, check=True)
    id_token = out.stdout.strip().splitlines()[-1]
    me = call(f"{API}/api/auth/me", None, id_token)
    print(f"backend user row: id={me.get('id')} (email absent = phone-only, correct)")
    code = DEMO_OTP or "<the fixed code from Identity Platform>"
    print(f"READY — sign in with {DEMO_PHONE}, code {code}.")


if __name__ == "__main__":
    main()
