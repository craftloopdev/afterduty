#!/usr/bin/env python3
"""
After Duty regression test suite.

Uses real Firebase custom tokens exchanged for ID tokens, so it tests the
actual production auth path (not dev-mode email header).

Usage:
    API_URL=https://api.example.com \\
    FIREBASE_API_KEY=... FIREBASE_PROJECT_ID=... \\
    GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json \\
        python3 regression.py
"""

import argparse
import json
import os
import random
import sys
import time
from pathlib import Path

import requests

# Required: Firebase config read from env (set FIREBASE_API_KEY and
# FIREBASE_PROJECT_ID; matches the values used by the Flutter app).
FIREBASE_API_KEY = os.environ.get("FIREBASE_API_KEY", "")
FIREBASE_PROJECT_ID = os.environ.get("FIREBASE_PROJECT_ID", "")
TEST_USER_UID = os.environ.get("FIREBASE_TEST_UID", "regression-test-user")
TEST_USER_EMAIL = os.environ.get("FIREBASE_TEST_EMAIL", "regression@test.example.com")

# Colors
GREEN = "\033[0;32m"
RED = "\033[0;31m"
YELLOW = "\033[1;33m"
NC = "\033[0m"

# GOOGLE_APPLICATION_CREDENTIALS must be set by the caller — points to a
# service-account key with iam.serviceAccountTokenCreator on the Firebase
# Admin SDK SA. We don't default a path here.

import subprocess


class TestRunner:
    def __init__(self, api_url):
        self.api_url = api_url.rstrip("/")
        self.id_token = None
        self.passed = 0
        self.failed = 0
        self.failed_tests = []
        self.evidence_ids_to_clean = []

    def log(self, name, ok, detail=""):
        if ok:
            print(f"{GREEN}✓ {name}{NC}" + (f" ({detail})" if detail else ""))
            self.passed += 1
        else:
            print(f"{RED}✗ {name}{NC}" + (f" ({detail})" if detail else ""))
            self.failed += 1
            self.failed_tests.append(name)

    def mint_id_token(self):
        """Mint a Firebase ID token via the mint_token.py helper, which uses
        IAM signJwt impersonation of the Firebase Admin SDK SA."""
        helper = Path(__file__).parent / "mint_token.py"
        try:
            result = subprocess.run(
                ["python3", str(helper)],
                capture_output=True,
                text=True,
                timeout=30,
            )
            if result.returncode != 0:
                print(f"{YELLOW}Token mint failed: {result.stderr.strip()[:300]}{NC}")
                return None
            return result.stdout.strip()
        except Exception as e:
            print(f"{YELLOW}Token mint error: {e}{NC}")
            return None

    def headers(self, auth=True):
        h = {"Content-Type": "application/json"}
        if auth and self.id_token:
            h["Authorization"] = f"Bearer {self.id_token}"
        return h

    def api(self, method, path, **kwargs):
        url = f"{self.api_url}{path}"
        kwargs.setdefault("headers", self.headers())
        kwargs.setdefault("timeout", 30)
        return requests.request(method, url, **kwargs)

    # ─────────────────────────── Tests ───────────────────────────

    def test_health(self):
        r = requests.get(f"{self.api_url}/health", timeout=10)
        ok = r.status_code == 200 and r.json().get("service") == "afterduty-api"
        self.log("Health check", ok, f"version={r.json().get('version')}")

    def test_cors(self):
        r = requests.options(
            f"{self.api_url}/api/auth/me",
            headers={
                "Origin": os.environ.get("WEB_ORIGIN", "https://app.afterduty.app"),
                "Access-Control-Request-Method": "GET",
                "Access-Control-Request-Headers": "authorization,content-type",
            },
            timeout=10,
        )
        self.log("CORS preflight", r.status_code == 200)

    def test_unauth_rejected(self):
        r = requests.get(f"{self.api_url}/api/auth/me", timeout=10)
        self.log("Unauthenticated request rejected", r.status_code in (401, 403))

    def test_invalid_token_rejected(self):
        r = requests.get(
            f"{self.api_url}/api/auth/me",
            headers={"Authorization": "Bearer invalid.token.here"},
            timeout=10,
        )
        self.log("Invalid Firebase token rejected", r.status_code in (401, 403))

    def test_real_token_auth(self):
        """Test that a real minted Firebase ID token is accepted."""
        if not self.id_token:
            self.log("Real Firebase token accepted", False, "no token minted")
            return
        r = self.api("GET", "/api/auth/me")
        self.log(
            "Real Firebase token accepted",
            r.status_code == 200,
            f"status={r.status_code}",
        )

    def test_get_claim(self):
        r = self.api("GET", "/api/claim")
        self.log("GET /api/claim", r.status_code == 200, f"status={r.status_code}")
        return r.json() if r.status_code == 200 else None

    def test_get_conditions(self):
        r = self.api("GET", "/api/claim/conditions")
        count = len(r.json()) if r.status_code == 200 and isinstance(r.json(), list) else 0
        self.log("GET /api/claim/conditions", r.status_code == 200, f"{count} conditions")

    def test_get_evidence(self):
        r = self.api("GET", "/api/claim/evidence")
        count = len(r.json()) if r.status_code == 200 and isinstance(r.json(), list) else 0
        self.log("GET /api/claim/evidence", r.status_code == 200, f"{count} items")

    def test_add_evidence_e2e(self):
        """End-to-end: upload a file, verify it's returned, delete it."""
        if not self.id_token:
            self.log("Add evidence E2E", False, "no auth token")
            return

        # Upload
        unique_name = f"regression-test-{int(time.time())}-{random.randint(1000,9999)}.txt"
        content = f"Test evidence for regression suite - {unique_name}"

        files = {"file": (unique_name, content.encode(), "text/plain")}
        data = {"source_type": "upload"}
        headers = {"Authorization": f"Bearer {self.id_token}"}
        r = requests.post(
            f"{self.api_url}/api/claim/evidence",
            files=files,
            data=data,
            headers=headers,
            timeout=30,
        )
        if r.status_code not in (200, 201):
            self.log(
                "Upload evidence file",
                False,
                f"status={r.status_code} body={r.text[:200]}",
            )
            return
        evidence = r.json()
        evidence_id = evidence.get("id")
        self.log("Upload evidence file", evidence_id is not None, f"id={evidence_id}")
        if not evidence_id:
            return
        self.evidence_ids_to_clean.append(evidence_id)

        # Verify it appears in the list
        r = self.api("GET", "/api/claim/evidence")
        items = r.json() if r.status_code == 200 else []
        found = any(i.get("id") == evidence_id for i in items)
        self.log("Uploaded evidence appears in list", found)

        # Verify filename was saved
        if found:
            item = next(i for i in items if i.get("id") == evidence_id)
            self.log(
                "Uploaded evidence has correct filename",
                item.get("filename") == unique_name,
                f"got={item.get('filename')}",
            )

        # Delete
        r = self.api("DELETE", f"/api/claim/evidence/{evidence_id}")
        self.log("Delete evidence", r.status_code in (200, 204), f"status={r.status_code}")
        if r.status_code in (200, 204):
            self.evidence_ids_to_clean.remove(evidence_id)

        # Verify deletion
        r = self.api("GET", "/api/claim/evidence")
        items = r.json() if r.status_code == 200 else []
        gone = not any(i.get("id") == evidence_id for i in items)
        self.log("Deleted evidence removed from list", gone)

    def test_conditions_e2e(self):
        """End-to-end: verify conditions endpoint returns proper structure."""
        r = self.api("GET", "/api/claim/conditions")
        if r.status_code != 200:
            self.log("Conditions endpoint returns 200", False, f"status={r.status_code}")
            return
        self.log("Conditions endpoint returns 200", True)

        data = r.json()
        if not isinstance(data, list):
            self.log("Conditions is a list", False, f"got {type(data).__name__}")
            return
        self.log("Conditions is a list", True)

        # If there are conditions, validate structure of first one
        if data:
            c = data[0]
            required_fields = ["id", "name", "vasrdCode", "estimatedRating", "confidence"]
            missing = [f for f in required_fields if f not in c]
            self.log(
                "Condition has required fields",
                len(missing) == 0,
                f"missing={missing}" if missing else f"sample={c.get('name')}",
            )

            # Verify triad structure
            has_triad = all(
                isinstance(c.get(t), dict)
                for t in ["triadDiagnosis", "triadInService", "triadNexus"]
            )
            self.log("Condition has triad structure", has_triad)
        else:
            self.log(
                "Conditions list is empty (new user)",
                True,
                "expected for fresh test user",
            )

    def test_messages(self):
        r = self.api("GET", "/api/claim/messages")
        self.log("GET /api/claim/messages", r.status_code == 200)

    def test_profile(self):
        r = self.api("GET", "/api/auth/profile")
        self.log("GET /api/auth/profile", r.status_code in (200, 204))

    def test_pipeline_metrics(self):
        r = self.api("GET", "/api/claim/pipeline-metrics")
        self.log("GET /api/claim/pipeline-metrics", r.status_code == 200)

    def test_chat(self):
        r = self.api(
            "POST",
            "/api/claim/chat",
            json={"message": "regression test message"},
        )
        self.log("POST /api/claim/chat", r.status_code in (200, 201))

    def test_vasrd_search(self):
        r = self.api("GET", "/api/vasrd/search?q=asthma")
        self.log("GET /api/vasrd/search", r.status_code == 200)

    def test_frontend(self):
        web_url = self.api_url.replace("va-claim-api", "va-claim-web-next")
        r = requests.get(f"{web_url}/", timeout=10)
        self.log("Frontend reachable", r.status_code == 200)

    # ─────────────────────────── Cleanup ───────────────────────────

    def cleanup(self):
        for eid in self.evidence_ids_to_clean:
            try:
                self.api("DELETE", f"/api/claim/evidence/{eid}")
            except Exception:
                pass

    def run_all(self):
        print(f"{'='*50}")
        print(f"After Duty Regression Tests")
        print(f"API: {self.api_url}")
        print(f"{'='*50}\n")

        # Public endpoint tests
        print(f"{YELLOW}── Public endpoints ──{NC}")
        self.test_health()
        self.test_cors()
        self.test_unauth_rejected()
        self.test_invalid_token_rejected()
        self.test_frontend()

        # Mint Firebase token for authenticated tests
        print(f"\n{YELLOW}── Authenticating ──{NC}")
        print("Minting Firebase ID token via custom token exchange...")
        self.id_token = self.mint_id_token()
        if self.id_token:
            print(f"{GREEN}✓ Token minted ({len(self.id_token)} chars){NC}")
        else:
            print(f"{RED}✗ Could not mint token — skipping authenticated tests{NC}")

        # Authenticated endpoint tests
        print(f"\n{YELLOW}── Authenticated endpoints ──{NC}")
        self.test_real_token_auth()
        self.test_get_claim()
        self.test_get_conditions()
        self.test_get_evidence()
        self.test_messages()
        self.test_profile()
        self.test_pipeline_metrics()
        self.test_chat()
        self.test_vasrd_search()

        # End-to-end flows
        print(f"\n{YELLOW}── End-to-end flows ──{NC}")
        self.test_add_evidence_e2e()
        self.test_conditions_e2e()

        # Cleanup
        self.cleanup()

        # Summary
        print(f"\n{'='*50}")
        print(
            f"Results: {GREEN}{self.passed} passed{NC}, "
            f"{RED}{self.failed} failed{NC}"
        )
        print(f"{'='*50}")
        if self.failed:
            print(f"{RED}Failed tests:{NC}")
            for t in self.failed_tests:
                print(f"  - {t}")
            return 1
        return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--api-url",
        default=os.environ.get("API_URL", "https://api.afterduty.app"),
    )
    args = parser.parse_args()

    runner = TestRunner(args.api_url)
    return runner.run_all()


if __name__ == "__main__":
    sys.exit(main())
