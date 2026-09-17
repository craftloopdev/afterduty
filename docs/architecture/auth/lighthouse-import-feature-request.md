# Feature Request: Import Veteran VA Data via VA Lighthouse APIs

**Type:** Feature
**Component:** Data ingestion / VA integration
**Auth dependency:** VA Lighthouse OAuth (veteran signs in via **ID.me** or Login.gov at VA's page)
**Data classification:** HIPAA PHI + PII → BAA-covered GCP store

> Items marked `[VERIFY]` are federal/API specifics that change and **must** be confirmed on `developer.va.gov` before build.

---

## 1. Summary
Let a veteran authorize VAClaimPath to pull their own VA records directly, instead of manually uploading everything, so the app can help document service-connected conditions from source data. Authorization happens through VA's Lighthouse OAuth server; the veteran chooses ID.me (or Login.gov) as their sign-in method **on VA's page** — the app never handles VA credentials.

## 2. Motivation
- Removes manual-upload friction for veterans who *do* have VA-side identity.
- Source-of-truth data is cleaner and more complete than scanned PDFs.
- Keeps VAClaimPath out of the credential-handling business (VA + ID.me own identity).
- Preserves the low-friction path for everyone else: manual upload remains fully supported (see §7).

## 3. Scope clarification — what "VA data" means here
**In scope (via Lighthouse):** health/clinical records, claim status/history, disability ratings, service history. Candidate APIs `[VERIFY]` exact names/versions:
- Veterans Health API (FHIR R4) — clinical data.
- Benefits Claims API — claim status/history.
- Veteran service history / disability rating API — ratings + service periods.

**Out of scope for this feature — NOT a Lighthouse capability:**
- **DD-214 retrieval.** The DD-214 is not served by Lighthouse (that's NARA / milConnect territory). DD-214 stays on the **manual-upload** path. Do not spec a Lighthouse call for it.

## 4. User stories
- As a veteran **with** an ID.me/Login.gov account, I can tap "Import my VA records," sign in on VA's page, consent to scopes, and have my records imported — without uploading files.
- As a veteran **without** VA-side identity, I am unaffected: I upload my own documents (D‑214, private records) and use the app normally.
- As a veteran, I can see exactly what was imported, and revoke access / delete imported data.

## 5. Technical approach

### 5.1 Authorization (OAuth 2.0 authorization code + PKCE)
1. App initiates the Lighthouse authorization request (per-API `scope`, `state`, `nonce`, PKCE `code_challenge`), redirect to VA's authorization server.
2. Veteran signs in **at VA** choosing **ID.me** or Login.gov; VA handles their MFA/proofing. `[VERIFY]` that ID.me is still an accepted CSP for the target API's auth server.
3. Redirect back with `code`; backend exchanges for `access_token` (+ `refresh_token` where offered) server-side using `code_verifier`.
4. Call the API(s) with the token; store results in the BAA GCP store.
5. Trigger this behind a **step-up** (see `auth-security-flows.md` §4).

### 5.2 Scopes
Per-API scopes `[VERIFY]` (e.g., FHIR patient-read scopes such as `launch/patient` + resource `.read` scopes for the Health API; claims scopes for Benefits). Request the **minimum** needed for claim documentation.

### 5.3 Consumer onboarding — **gating dependency, plan the timeline**
- Register as a consumer on `developer.va.gov`; obtain **sandbox** access first.
- **Production** access to veteran PHI requires a VA review/approval process (demonstrating security + compliance posture). This is a real timeline item, not a config toggle. `[VERIFY]` current eligibility + steps. Do not assume production access is immediate.

### 5.4 Data handling & compliance
- Imported PHI lands only in the BAA-covered GCP store; encrypted at rest and in transit.
- Explicit **consent capture** before import; show the veteran the scopes and what will be pulled.
- **Audit-log** every import (who, when, scopes, records).
- **Revocation + delete:** honor token revocation and provide a user-facing "delete my imported VA data."
- Token storage server-side, encrypted; never in the client.

## 6. Acceptance criteria
- [ ] Veteran can complete VA sign-in via ID.me/Login.gov and authorize import in sandbox.
- [ ] Only minimum required scopes are requested; consent screen lists them.
- [ ] Imported data is written to the BAA store and visible to the veteran.
- [ ] Import is gated behind a fresh second-factor step-up.
- [ ] Veteran can revoke access and delete imported data; both are audit-logged.
- [ ] Manual-upload path is unchanged and still handles DD-214.

## 7. Out of scope (v1)
- DD-214 via Lighthouse (manual upload only).
- Real-time / scheduled re-sync (v1 is on-demand pull).
- Cross-device passkey hybrid transport for VA sign-in.

## 8. Open questions / verify before build
- `[VERIFY]` Is **ID.me** still an accepted CSP for the target Lighthouse auth server, or has VA moved to Login.gov-only for these APIs? Confirm both are offered at sign-in.
- `[VERIFY]` Exact API names, versions, and scopes for health / claims / ratings.
- `[VERIFY]` Current production-access eligibility + review process for veteran PHI.
- `[VERIFY]` Refresh-token availability and lifetime per API (affects re-pull UX).
