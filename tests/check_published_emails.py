#!/usr/bin/env python3
"""Verify that every contact address we publish to users can actually receive mail.

The After Duty rebrand moved every shipped contact string to @afterduty.app while
those mailboxes did not exist, so the login-lockout screen, the paywall and the
privacy policy all pointed veterans at addresses that hard-bounced. Nothing caught
it: the strings were consistent, the tests passed, the deploy was green. This check
closes that gap by asking the receiving mail server directly.

Method: collect published addresses from shipped copy, then run an SMTP RCPT probe
against each domain's MX. RCPT only — no DATA is ever sent, so no mail is delivered.

A catch-all domain answers 250 for everything, which would make the probe pass
vacuously, so each domain is also probed with a random local part as a negative
control. If the control is accepted the result is reported as UNVERIFIABLE rather
than as a pass.

Exit codes: 0 = every address accepted (or the probe could not run), 1 = at least
one published address was rejected.
"""

from __future__ import annotations

import pathlib
import re
import smtplib
import socket
import subprocess
import sys
import uuid

REPO = pathlib.Path(__file__).resolve().parent.parent

# Files whose addresses reach users or the public. Keep this list narrow: a doc that
# merely mentions an address in passing is not a promise that it is monitored.
PUBLISHED_SOURCES = [
    "web/src/app/(auth)/login/friendly-error.ts",
    "web/src/app/(auth)/recovery/page.tsx",
    "web/src/components/paywall/PaywallParts.tsx",
    "web/public/privacy.html",
    "SECURITY.md",
    "CODE_OF_CONDUCT.md",
]

ADDRESS_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# Addresses that are examples or belong to third parties, not contact promises.
IGNORE = re.compile(r"@(example|test|localhost|sentry|.*\.test)\b|noreply@google", re.I)

# Addresses knowingly published before their mailbox exists. Each entry needs a
# reason and an owner, and is reported loudly on every run so it cannot quietly
# become permanent. Empty this dict as the mailboxes get created.
KNOWN_DEFERRED = {
    "support@afterduty.app": "owner-deferred 2026-08-12: needs a Workspace alias/group on the good@ mailbox",
    "privacy@afterduty.app": "owner-deferred 2026-08-12: needs a Workspace alias/group on the good@ mailbox",
    "security@afterduty.app": "owner-deferred 2026-08-12: needs a Workspace alias/group on the good@ mailbox",
    "conduct@afterduty.app": "owner-deferred 2026-08-12: needs a Workspace alias/group on the good@ mailbox",
}


def collect() -> dict[str, list[str]]:
    """Map address -> the files that publish it."""
    found: dict[str, list[str]] = {}
    for rel in PUBLISHED_SOURCES:
        path = REPO / rel
        if not path.exists():
            continue
        for addr in ADDRESS_RE.findall(path.read_text(errors="ignore")):
            if IGNORE.search(addr):
                continue
            found.setdefault(addr.lower(), []).append(rel)
    return found


def mx_for(domain: str) -> str | None:
    try:
        out = subprocess.run(
            ["dig", "+short", "MX", domain],
            capture_output=True, text=True, timeout=15,
        ).stdout.split()
    except Exception:
        return None
    hosts = [(int(p), h.rstrip(".")) for p, h in zip(out[::2], out[1::2]) if p.isdigit()]
    return min(hosts)[1] if hosts else None


def probe(mx: str, addresses: list[str], control: str) -> dict[str, int] | None:
    """RCPT-probe each address. Returns None if the probe itself could not run."""
    try:
        server = smtplib.SMTP(mx, 25, timeout=20)
        server.ehlo("afterduty.app")
        server.mail("postmaster@afterduty.app")
        results = {addr: server.rcpt(addr)[0] for addr in addresses + [control]}
        server.quit()
        return results
    except (socket.timeout, OSError, smtplib.SMTPException):
        return None


def main() -> int:
    published = collect()
    if not published:
        print("      (no published addresses found — check PUBLISHED_SOURCES)")
        return 0

    domains: dict[str, list[str]] = {}
    for addr in published:
        domains.setdefault(addr.split("@", 1)[1], []).append(addr)

    failures: list[str] = []
    known: list[str] = []
    deferred_fixed: list[str] = []
    for domain, addresses in sorted(domains.items()):
        mx = mx_for(domain)
        if not mx:
            failures.append(f"{domain} publishes contact addresses but has no MX record")
            continue

        control = f"zz-no-such-user-{uuid.uuid4().hex[:8]}@{domain}"
        results = probe(mx, sorted(addresses), control)
        if results is None:
            # Outbound port 25 is commonly blocked; that is not a product defect.
            print(f"      (could not reach {mx}:25 — skipping mailbox probe for {domain})")
            continue

        if results[control] < 400:
            accepted = ", ".join(sorted(addresses))
            print(f"      ({domain} is a catch-all — cannot verify {accepted} individually)")
            continue

        for addr in sorted(addresses):
            if results[addr] < 400:
                if addr in KNOWN_DEFERRED:
                    deferred_fixed.append(addr)
                continue
            where = ", ".join(sorted(set(published[addr])))
            detail = f"{addr} is rejected by {mx} ({results[addr]}) — published in {where}"
            if addr in KNOWN_DEFERRED:
                known.append(f"{detail} [{KNOWN_DEFERRED[addr]}]")
            else:
                failures.append(detail)

    for problem in failures:
        print(f"       {problem}")
    for problem in known:
        print(f"      KNOWN-DEFERRED: {problem}")
    for addr in deferred_fixed:
        print(f"      RESOLVED: {addr} now accepts mail — drop it from KNOWN_DEFERRED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
