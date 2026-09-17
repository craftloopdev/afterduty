#!/usr/bin/env python3
"""
End-to-end upload flow test:
1. Upload a test medical document
2. Poll evidence status until processing completes
3. Trigger analysis (synthesis + conditions)
4. Poll claim status until analysis completes
5. Verify conditions were extracted
6. Clean up

This test validates the complete user journey and measures timing
so we know what to expect for real uploads.
"""

import os
import sys
import time
import json
import subprocess
from pathlib import Path

import requests

API_URL = "https://api.afterduty.app"
TEST_DOC = Path(__file__).parent / "test_fixtures" / "test_health_summary.txt"
TIMEOUT_EXTRACTION = 180  # 3 min
TIMEOUT_ANALYSIS = 300    # 5 min
POLL_INTERVAL = 5

GREEN = "\033[0;32m"
RED = "\033[0;31m"
YELLOW = "\033[1;33m"
NC = "\033[0m"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def mint_token():
    helper = Path(__file__).parent / "mint_token.py"
    result = subprocess.run(
        ["python3", str(helper)],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0:
        print(f"Mint failed: {result.stderr}")
        sys.exit(1)
    return result.stdout.strip()


def main():
    if not TEST_DOC.exists():
        print(f"{RED}Test document not found at {TEST_DOC}{NC}")
        sys.exit(1)

    log("Minting Firebase token...")
    token = mint_token()
    headers = {"Authorization": f"Bearer {token}"}

    # Clean up any previous uploads of this test document first
    log("Cleaning up any existing test evidence...")
    r = requests.get(f"{API_URL}/api/claim/evidence", headers=headers, timeout=30)
    if r.status_code == 200:
        for item in r.json():
            if item.get("filename", "").startswith("test_health_summary"):
                requests.delete(
                    f"{API_URL}/api/claim/evidence/{item['id']}",
                    headers=headers,
                    timeout=30,
                )
                log(f"  Deleted existing evidence {item['id']}")

    # Use a unique filename per run
    unique_name = f"test_health_summary_{int(time.time())}.txt"
    content = TEST_DOC.read_bytes()

    # ── STAGE 1: UPLOAD ──
    log(f"{YELLOW}STAGE 1: Uploading {unique_name} ({len(content)} bytes){NC}")
    files = {"file": (unique_name, content, "text/plain")}
    data = {"source_type": "upload"}
    t0 = time.time()
    r = requests.post(
        f"{API_URL}/api/claim/evidence",
        files=files,
        data=data,
        headers=headers,
        timeout=30,
    )
    upload_time = time.time() - t0
    if r.status_code not in (200, 201):
        print(f"{RED}✗ Upload failed: {r.status_code} {r.text[:200]}{NC}")
        return 1

    evidence = r.json()
    evidence_id = evidence["id"]
    log(f"{GREEN}✓ Upload returned {r.status_code} in {upload_time:.2f}s, evidence_id={evidence_id}{NC}")

    # ── STAGE 2: POLL FOR EXTRACTION COMPLETE ──
    log(f"{YELLOW}STAGE 2: Waiting for atom extraction (up to {TIMEOUT_EXTRACTION}s){NC}")
    log("  User would see: 'AI is reading through your document...'")

    extraction_start = time.time()
    last_status = None
    while True:
        elapsed = time.time() - extraction_start
        if elapsed > TIMEOUT_EXTRACTION:
            print(f"{RED}✗ Extraction timed out after {elapsed:.0f}s{NC}")
            return 1

        r = requests.get(f"{API_URL}/api/claim/evidence", headers=headers, timeout=30)
        if r.status_code == 200:
            item = next((i for i in r.json() if i["id"] == evidence_id), None)
            if item:
                status = item.get("processingStatus", "?")
                message = item.get("processingMessage", "")
                atom_count = (item.get("aiExtractedData") or {}).get("atom_count", 0)

                if status != last_status:
                    log(f"  [{elapsed:5.1f}s] status={status} atoms={atom_count} msg={message[:80]}")
                    last_status = status

                if status == "processed":
                    log(f"{GREEN}✓ Extraction complete in {elapsed:.1f}s: {atom_count} atoms{NC}")
                    if atom_count == 0:
                        print(f"{RED}✗ Zero atoms extracted — extraction silently failed{NC}")
                        return 1
                    break
                if status == "failed":
                    print(f"{RED}✗ Extraction failed: {message}{NC}")
                    return 1

        time.sleep(POLL_INTERVAL)

    # ── STAGE 3: TRIGGER ANALYSIS ──
    log(f"{YELLOW}STAGE 3: Triggering claim analysis (synthesis + conditions){NC}")
    r = requests.post(f"{API_URL}/api/claim/analyze", headers=headers, timeout=60)
    if r.status_code not in (200, 201, 202):
        print(f"{RED}✗ Analyze failed: {r.status_code} {r.text[:200]}{NC}")
        return 1
    log(f"{GREEN}✓ Analysis started (status {r.status_code}){NC}")

    # ── STAGE 4: POLL FOR ANALYSIS COMPLETE ──
    log(f"{YELLOW}STAGE 4: Waiting for synthesis (up to {TIMEOUT_ANALYSIS}s){NC}")
    log("  User would see: 'Analyzing your evidence and identifying conditions...'")

    analysis_start = time.time()
    while True:
        elapsed = time.time() - analysis_start
        if elapsed > TIMEOUT_ANALYSIS:
            print(f"{RED}✗ Analysis timed out after {elapsed:.0f}s{NC}")
            return 1

        r = requests.get(f"{API_URL}/api/claim", headers=headers, timeout=30)
        if r.status_code == 200:
            claim = r.json()
            status = claim.get("status", "?")
            cond_count = claim.get("conditionCount", 0)
            if elapsed > 0 and int(elapsed) % 15 == 0:
                log(f"  [{elapsed:5.1f}s] claim_status={status} conditions={cond_count}")

            if status == "ANALYZED":
                log(f"{GREEN}✓ Analysis complete in {elapsed:.1f}s: {cond_count} conditions{NC}")
                break
            if status == "FAILED" or status == "ERROR":
                print(f"{RED}✗ Analysis failed{NC}")
                return 1
        time.sleep(POLL_INTERVAL)

    # ── STAGE 5: VALIDATE CONDITIONS ──
    log(f"{YELLOW}STAGE 5: Validating extracted conditions{NC}")
    r = requests.get(f"{API_URL}/api/claim/conditions", headers=headers, timeout=30)
    if r.status_code != 200:
        print(f"{RED}✗ Could not fetch conditions: {r.status_code}{NC}")
        return 1

    conditions = r.json()
    log(f"  Found {len(conditions)} conditions:")
    for c in conditions:
        name = c.get("name", "?")
        code = c.get("vasrdCode", "?")
        rating = c.get("estimatedRating", "?")
        log(f"    - {name} ({code}) at {rating}%")

    if len(conditions) == 0:
        print(f"{RED}✗ No conditions extracted — synthesis failed{NC}")
        return 1

    # Check for expected conditions from the test document
    expected = ["asthma", "ptsd", "sleep apnea"]
    found_names = [c.get("name", "").lower() for c in conditions]
    found = [
        e for e in expected
        if any(e in n for n in found_names)
    ]
    missing = [e for e in expected if e not in found]

    if missing:
        print(f"{YELLOW}⚠ Some expected conditions missing: {missing}{NC}")
    else:
        print(f"{GREEN}✓ All expected conditions found: {expected}{NC}")

    total_time = upload_time + (time.time() - extraction_start)
    log(f"\n{GREEN}{'='*60}")
    log(f"FULL FLOW COMPLETE in {total_time:.1f}s")
    log(f"  Upload:     {upload_time:.1f}s")
    log(f"  Extraction: {time.time() - extraction_start - (time.time() - analysis_start):.1f}s")
    log(f"  Analysis:   {time.time() - analysis_start:.1f}s")
    log(f"  Atoms extracted: {atom_count}")
    log(f"  Conditions:      {len(conditions)}")
    log(f"{'='*60}{NC}")

    # ── CLEANUP ──
    requests.delete(
        f"{API_URL}/api/claim/evidence/{evidence_id}",
        headers=headers,
        timeout=30,
    )
    log(f"Cleaned up evidence {evidence_id}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
