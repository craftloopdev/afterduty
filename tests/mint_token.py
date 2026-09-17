#!/usr/bin/env python3
"""Mint a Firebase ID token via IAM signJwt impersonation.
Returns the ID token on stdout. Used by regression tests.

Required env vars:
  FIREBASE_API_KEY    Firebase Web API key for the target project
  FIREBASE_PROJECT_ID GCP project id hosting the Firebase Admin SDK SA
                      (used to construct the SA email)
  GOOGLE_APPLICATION_CREDENTIALS  service-account JSON for the source
                      identity that has roles/iam.serviceAccountTokenCreator
                      on firebase-adminsdk-* in the target project
"""
import os
import sys
import json
import time

import requests
import google.auth
from google.auth.transport.requests import Request

FIREBASE_API_KEY = os.environ.get("FIREBASE_API_KEY")
FIREBASE_PROJECT_ID = os.environ.get("FIREBASE_PROJECT_ID")
if not FIREBASE_API_KEY or not FIREBASE_PROJECT_ID:
    print(
        "Missing FIREBASE_API_KEY or FIREBASE_PROJECT_ID env var.",
        file=sys.stderr,
    )
    sys.exit(2)

FIREBASE_SA = f"firebase-adminsdk-fbsvc@{FIREBASE_PROJECT_ID}.iam.gserviceaccount.com"
TEST_UID = os.environ.get("FIREBASE_TEST_UID", "regression-test-user")
TEST_EMAIL = os.environ.get("FIREBASE_TEST_EMAIL", "regression@test.example.com")

# Get source creds (must have iam.serviceAccountTokenCreator on FIREBASE_SA)
creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
creds.refresh(Request())

# Build the JWT payload for a Firebase custom token
now = int(time.time())
payload = {
    "iss": FIREBASE_SA,
    "sub": FIREBASE_SA,
    "aud": "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
    "uid": TEST_UID,
    "iat": now,
    "exp": now + 3600,
    "claims": {"email": TEST_EMAIL},
}

# Sign the JWT via IAM signJwt (impersonating firebase-adminsdk SA)
resp = requests.post(
    f"https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/{FIREBASE_SA}:signJwt",
    headers={"Authorization": f"Bearer {creds.token}"},
    json={"payload": json.dumps(payload)},
    timeout=15,
)
if resp.status_code != 200:
    print(f"signJwt failed: {resp.status_code} {resp.text}", file=sys.stderr)
    sys.exit(1)

custom_token = resp.json()["signedJwt"]

# Exchange for ID token
resp = requests.post(
    "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken",
    params={"key": FIREBASE_API_KEY},
    json={"token": custom_token, "returnSecureToken": True},
    timeout=15,
)
if resp.status_code != 200:
    print(f"signInWithCustomToken failed: {resp.status_code} {resp.text}", file=sys.stderr)
    sys.exit(1)

print(resp.json()["idToken"])
