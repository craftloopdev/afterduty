# After Duty (formerly VA Claim Path)

A web app that helps US veterans organize and strengthen their VA disability
claim. Sign in, upload medical records and DD-214s, and AI extracts structured
facts from each document. The system maps those facts to the VA's three-part
standard (diagnosis, in-service event, nexus) per condition, computes an
estimated combined rating, lists evidence gaps to chase next, and produces a
read-only share link the veteran can send to a VSO or attorney.

Free tier is document storage + share-with-VSO. Pro tier adds AI extraction,
synthesis, and gap analysis.

> **Educational use only.** This is not legal advice. Veterans should consult
> a VA-accredited representative or attorney for their specific claim.

## Stack

- **Backend** — Spring Boot 4.0, Java 21, Gradle Kotlin DSL, JPA + Postgres
  (or H2 for local dev), Stripe, Firebase Admin SDK, Anthropic Claude +
  Google Vertex Gemini.
- **Frontend** — Next.js 16 (BFF pattern) + React, with Capacitor shells for
  the iOS and Android apps. Auth is passwordless OTP (phone or email) via
  Firebase.
- **Deploy** — Google Cloud Run (both services), Cloud Build, Cloud SQL.

## Quick start

### Prerequisites

- Java 21 (`brew install openjdk@21` on macOS)
- Node 20+
- A Postgres instance for local dev (or fall back to H2 — backend auto-detects
  via Spring profile)
- Optional for full functionality: a Firebase project, a Stripe test account,
  and an Anthropic API key

### Backend

```bash
cd spring-backend
cp .env.example .env       # then fill in the values you have
export $(grep -v '^#' .env | xargs)
./gradlew bootRun --args='--spring.profiles.active=local'
```

The local profile defaults to H2 in-memory. Set `DB_PASSWORD` plus a Postgres
URL in `application-local.yml` to swap in real Postgres.

### Frontend

```bash
cd web
npm install
npm run dev
```

The dev server proxies API calls to the backend at `http://localhost:8080`.
The Capacitor iOS/Android shells build from the same `web/` app — see
`web/ios/` and `web/android/`.

## Repository layout

```
spring-backend/        Spring Boot API
web/                   Next.js BFF + Capacitor iOS/Android shells
docs/                  Architecture, security, and QA docs
tests/                 Prod verification tooling
regression/            Manifest of regression tests per feature
branding/              Brand mark masters (SVG) — `web/scripts/generate-icons.mjs` derives every icon
.github/               Issue / PR templates and workflows
```

## Running tests

```bash
# Backend — full suite
cd spring-backend && ./gradlew test

# Backend — tagged regression sweep (used before prod deploys)
cd spring-backend && ./gradlew test -PregressionOnly

# Frontend (vitest)
cd web && npm test

# Frontend — end-to-end (Playwright)
cd web && npm run test:e2e
```

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for branch naming, PR process, and
how to run the regression suite. Security disclosures go to
[SECURITY.md](./SECURITY.md), not the public issue tracker.

## License

Apache 2.0 — see [LICENSE](./LICENSE).
