# Security Policy

After Duty handles sensitive veteran data — medical records, service
history, disability ratings. We take security reports seriously.

## Reporting a vulnerability

**Do not file a public GitHub issue for a security report.**

Preferred: [open a private GitHub Security Advisory](https://github.com/craftloopdev/afterduty/security/advisories/new).

Fallback email: `security@afterduty.app`.

Please include:
- A clear description of the issue and the impact you believe it has.
- Steps to reproduce, ideally with a minimal proof-of-concept.
- The commit SHA / deployed revision you tested against.
- Whether you've shared the report with anyone else.

We aim to:
- Acknowledge your report within **3 business days**.
- Provide a remediation timeline within **10 business days**.
- Credit you in the release notes if you'd like (otherwise we'll keep the
  report anonymous).

## Scope

In scope:
- The hosted app at `app.afterduty.app` (and the legacy `app.vaclaimpath.com`, which 301s there).
- The API at `api.afterduty.app` (and the legacy `api.vaclaimpath.com`, which dual-serves during the transition).
- The Spring backend and Next.js frontend (web/) in this repository, including the Capacitor iOS/Android shells.

Out of scope:
- Issues that require a compromised user device or local network.
- Social-engineering or physical-security attacks.
- Third-party dependencies — please report those directly to the maintainers.
- Denial-of-service via volumetric load.

## Safe harbor

We will not pursue legal action against researchers who:
- Make a good-faith effort to follow this policy.
- Avoid privacy violations, data destruction, and service interruption.
- Only access the minimum data necessary to demonstrate the issue.
- Delete any data they accessed once the report is closed.
