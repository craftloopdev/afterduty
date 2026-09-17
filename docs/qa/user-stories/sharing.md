# User Stories — Profile Sharing (Owner + Viewer)

**Status:** release gate for the sharing feature.
**Written:** 2026-09-08. **Verified against:** `worktree-sharing-user-stories` (branched from `engine/integration-pickup`).

This document is both the specification and the manual test script. Run it top to
bottom with two real accounts. Every story carries the code path that implements it
and a verdict:

| Verdict | Meaning |
|---|---|
| **PASS** | Traced to shipped code that satisfies the criteria. Verify by hand; expect it to work. |
| **GAP** | The criteria are not met by current code. The cause is named. Expect it to fail. |

Verdicts were established by reading the implementation, not by running the flow.
Confirm each one as you test — a PASS that fails in practice is the most valuable
thing this document can surface.

---

## 1. Pre-flight — read this first

The single most common reason a local test of this feature "goes nowhere" is a
setup problem, not a code problem. All four of these are load-bearing.

### 1.1 Both accounts must sign in with EMAIL, not phone

Accepting an invite compares the invited address against the viewer's **stored**
account email, case-insensitively:

```
ShareService.java:251-256  ->  403 email_mismatch
```

A phone-OTP account has no real email. It carries a synthetic placeholder
(`<uid>@firebase.local`, `AuthController.java:121-127`), which can never match an
invited address. **A phone-only viewer is permanently locked out of every invite**,
and the error copy they see ("This invite was created for a different email
address") does not tell them why or how to fix it.

*Workaround for testing:* the viewer attaches a real email via the OTP flow first
(`EmailCodeAuthController` mirrors it onto the users row), then accepts. See
**V-03b**.

### 1.2 Nothing is emailed — you deliver the link yourself

`ShareService` has no mail integration. The owner clicks **Copy link** and sends it
by their own email or text (`ShareManager.tsx:282-285`). If you were waiting on an
invite email, none was ever sent. This is intentional, and the UI says so.

### 1.3 The owner needs an active Pro subscription for analysis sharing

`VIEW_ANALYSIS` checks the **owner's** subscription, not the viewer's
(`ClaimAccessService.java:168-182`). A free owner's rep gets documents only, and the
viewer banner says so honestly. Put the owner on Pro before testing V-08.

### 1.4 The analysis toggle defaults to OFF

`ShareManager.tsx:104` — `useState(false)`. An invite sent without touching the
toggles is **documents-only**. If you are testing analysis sharing, turn it on
explicitly.

### 1.5 Test accounts

| Role | Requirement |
|---|---|
| **Owner (Alice)** | Email sign-in. Active Pro. At least one uploaded document and a completed analysis (conditions + next steps present). |
| **Viewer (Vince)** | Email sign-in, **different** address. Needs own Pro only for viewer chat (V-11). |
| **Owner 2 (Bob)** | Email sign-in. A second claim, shared with Vince — required for the multi-profile switching stories (V-07). |

---

## 2. What is shared

The intended contract, and what the code actually does today.

| Surface | Intended | Actual | Story |
|---|---|---|---|
| AI analysis — conditions, ratings, gaps, next steps | Shared when `canViewAnalysis` | **Shared** | V-08 |
| Document **list** — filenames, status, extracted facts | Shared | **Shared** | V-09a |
| Document **originals** — open / download the file | Shared | **Not shared** — 404 | V-09b |
| Profile information — service history, branch, periods | Shared | **Not shared** — viewer sees their own | V-10 |
| AI chat history | **Not** shared (deferred) | **Not shared** | V-11 |
| Document upload by the viewer | Shared when `canUploadDocs` | **Silently misrouted** | V-17 |
| Document deletion | Never | **Never** — owner only | X-04 |

Access is controlled on two independent axes, and every story below names both:

- **Which claim** — the `X-View-As: <claimId>` header, resolved by
  `ClaimAccessService.resolve` (`ClaimAccessService.java:113-135`). Set from the
  `cp_view_as` httpOnly cookie by `serverFetch`.
- **What within it** — the `canViewAnalysis` / `canUploadDocs` flags on the `shares`
  row, enforced by `ClaimAccessService.assertScope`
  (`ClaimAccessService.java:162-236`).

Viewer mode is **read-only at the transport layer**: `withAmbientViewAs` strips
`X-View-As` from every non-GET request (`transport.ts:43-49`). This is deliberate
defense in depth — and it is also why V-17 behaves the way it does.

---

## 3. Owner stories — sharing a profile

### O-01 — Find the sharing feature

**As a** veteran
**I want** to find where I invite my rep
**So that** I can get help without hunting for it

- **Given** I am signed in on my own claim
- **When** I look at Home, or open Profile
- **Then** I see a "Share with your VSO" card on Home, and a Share row in Profile
- **And** both lead to `/share`

`ShareCard.tsx`, `ProfileView.tsx` (Share row), `AppShell.tsx:54-55`.
Share is deliberately **not** in the sidebar or bottom nav (`Sidebar.tsx:8-14`,
`MobileNav.tsx:9-15`) — discoverability rests on those two entry points.

**Verdict: PASS.** Confirm both entry points are present and reachable.

---

### O-02 — Invite a trusted person, choosing what they see

**As a** veteran
**I want** to invite my rep by email and choose what they can see
**So that** I control how much of my claim I expose

- **Given** I am on `/share`
- **When** I enter my rep's email address
- **And** I set "Share analysis access" and "Allow document uploads" as I want them
- **And** I press "Create secure invite"
- **Then** an invite is created for exactly that address, with exactly those permissions
- **And** the email is normalized (trimmed, lowercased) so case can never cause a missed match later

`ShareManager.tsx:243-276`; `ShareService.java:69-73` (normalization),
`ShareService.java:110-121` (row creation, 7-day expiry).

**Both toggles default to OFF** (`ShareManager.tsx:104-105`). Test at least one
invite with analysis ON and one with it OFF — they produce visibly different viewer
experiences (V-08 vs V-12).

**Verdict: PASS.**

---

### O-03 — Deliver the invite

**As a** veteran
**I want** to get the invite into my rep's hands
**So that** they can actually take up the access I granted

- **Given** I just created an invite
- **When** the confirmation appears
- **Then** it tells me plainly that we do not email it for me
- **And** it shows the full invite link in a selectable field, with a **Copy link** button
- **And** it tells me the link expires in 7 days and works once
- **And** if the clipboard write fails, it tells me to copy the link manually rather than failing silently

`ShareManager.tsx:277-321`; the link prefers the backend-computed `acceptUrl`
(`ShareService.java:363-366`) over an origin-built fallback — which matters on
native, where `window.location.origin` is `https://localhost` and a copied link
would be dead on arrival.

**Verdict: PASS**, with the standing product caveat that delivery is manual. See
§1.2.

---

### O-04 — See who has access, and in what state

**As a** veteran
**I want** a register of everyone I have granted access to
**So that** I can audit who can see my claim right now

- **Given** I have created one or more invites
- **When** I open `/share`
- **Then** each person appears with their email, what they can do
  ("Analysis & documents" / "Documents only", "· can upload"),
  and a status pill: **Active**, **Invite sent**, **Expired**, or **Revoked**
- **And** each row carries a plain-language lifecycle line
  ("Accepted 12 August 2026", "Invite sent — expires 19 August 2026",
  "Invite expired — re-invite to send a fresh link")

`ShareManager.tsx:340-402`, `lifecycleLine` at `ShareManager.tsx:25-40`;
status precedence revoked > accepted > expired > pending
(`ShareService.computeStatus`, `ShareService.java:375-388`).

**Verdict: PASS.**

---

### O-05 — Know when a pending invite runs out

**As a** veteran who sent an invite a few days ago
**I want** to see when it expires
**So that** I know whether to nudge my rep

- **Given** a row with status "Invite sent"
- **When** I read it
- **Then** it names the expiry date
- **And** the post-creation confirmation states the remaining days

`ShareManager.tsx:31-34`, `ShareManager.tsx:307-312`, `inviteExpiresInDays`.

**Verdict: PASS.**

---

### O-06 — Re-invite when a link expired or went unused

**As a** veteran whose rep never clicked the link
**I want** to send a fresh one in one tap
**So that** an expired invite is not a dead end

- **Given** a row with status "Invite sent" or "Expired"
- **When** I press **Re-invite**
- **Then** a brand-new invite is created with the same email and the same permissions
- **And** the previous link stops working (it answers 410 `share_revoked`, a deliberate signal rather than a mystery 404)
- **And** at most one non-revoked share per (claim, email) still holds

`ShareManager.tsx:181-186`, `ShareManager.tsx:373-381`;
`ShareService.java:100-107` retires the stale row before issuing the new one.

**Verdict: PASS.** Worth testing explicitly — confirm the *old* link now shows
"This invite is no longer active" and the *new* one works.

---

### O-07 — Change what someone can see, without cutting them off

**As a** veteran whose rep now needs analysis access
**I want** to widen (or narrow) an existing share
**So that** I do not have to revoke and start over

- **Given** an accepted share with documents-only access
- **When** I try to grant analysis access
- **Then** the change applies to the existing share

`PATCH /api/shares/{id}` exists and works (`ShareController.java:53-61`,
`ShareService.java:140-158`), but **no UI calls it** — `ShareManager` never issues a
PATCH, and there is no BFF passthrough route.

**Verdict: GAP.** The owner's only path is to re-invite the same address with the
new permissions (O-06), which retires the pending row — or, for an already-accepted
share, silently refreshes the permissions in place (`ShareService.java:80-95`). That
second path *does* work, but it is completely undiscoverable: the owner has to
retype the email into the invite form and guess that it updates rather than
duplicates.

---

### O-08 — Cut off access immediately

**As a** veteran
**I want** to revoke access instantly and deliberately
**So that** I stay in control of my own records

- **Given** any active or pending share
- **When** I press the × on the row
- **Then** I get a confirmation dialog naming the person, and — for a pending invite — warning that their link will stop working
- **And** on confirming, access ends immediately
- **And** I see "Access revoked — {email} no longer has access to your claim. Any invite links you sent them are dead."
- **And** if the revoke fails, I am told, rather than the row silently staying put

`ShareManager.tsx:404-440` (modal), `ShareManager.tsx:204-227` (error handling),
`ShareManager.tsx:330-338` (post-revoke confirmation);
`ShareService.java:167-186` — revocation is idempotent and never moves `revokedAt`
forward on a repeat call.

**Verdict: PASS.**

---

### O-09 — My conversations with the AI stay mine

**As a** veteran
**I want** my AI chat history to stay private even from someone I trust with my claim
**So that** I can think out loud without an audience

- **Given** I have chatted with the AI about my claim
- **When** my rep views my claim
- **Then** they cannot see any message I sent or received

This holds **structurally**, not by filtering. Every `(viewer, claim)` pair gets its
own `chat_threads` row, created at accept time (`ShareService.java:265-274`), and
message reads are scoped to the caller's own thread (`ChatService.listMessages`,
`ChatService.java:217-222`) — returning an empty list when no thread exists.

**Verdict: PASS.** This is the "chats are not shared" requirement, and it is the
cleanest part of the feature. Chat *history* sharing is deliberately deferred to a
future session.

---

### O-10 — Understand that analysis sharing rides on my subscription

**As a** veteran on a lapsing plan
**I want** to know my rep loses analysis access if my Pro ends
**So that** I am not blindsided, and neither are they

- **Given** I am setting up a share
- **When** I read the analysis toggle
- **Then** I am told: "Analysis sharing requires your active Pro subscription. If it lapses, your rep keeps document access only."

`ShareManager.tsx:262-267`; enforced at `ClaimAccessService.java:168-182`
(`owner_subscription_required`). The viewer sees the matching honest notice — V-13.

**Verdict: PASS.** Disclosed up front rather than after the rep hits a wall.

---

### O-11 — Confirm that nobody has access

**As a** privacy-conscious veteran
**I want** positive confirmation that no one can see my claim
**So that** I can tell "no one has access" apart from "the list failed to load"

- **Given** I have never shared, or have revoked everyone
- **When** I open `/share`
- **Then** I see an explicit "No one currently has access" confirmation

`ShareManager.tsx:340` renders the whole "Shared with" section only when
`shares.length > 0`. With no shares, the section is simply absent.

**Verdict: GAP.** Low severity — the audit story is half-served. A veteran cannot
distinguish an empty register from a missing one.

---

## 4. Viewer stories — receiving and reading a shared profile

### V-01 — See what I am accepting before I sign in

**As a** VSO who received a link
**I want** to see who is inviting me and what I would get
**So that** I can decide before creating an account

- **Given** I open an invite link while signed out
- **When** the page loads
- **Then** I see the veteran's name, what the share grants ("See their conditions, ratings & gaps" or "See their documents", plus "Add documents on their behalf" when granted), and when the invite expires
- **And** I am not required to authenticate to see this

`AcceptShare.tsx:176-226`; `GET /api/shares/accept/{token}` is public
(`ShareController.java:73-76`, auth skipped in `SecurityConfig`).

**Verdict: PASS.**

---

### V-02 — Sign in and take up the access

**As a** VSO
**I want** to sign in and accept
**So that** I can start reviewing

- **Given** I am on the preview and press "Accept invite"
- **When** I am not signed in
- **Then** I go to `/login` and return to **this** invite afterwards
- **And** on accepting I see "You're in — you now have access to {owner}'s claim" with a way into the app
- **And** the invite link is single-use from then on

`AcceptShare.tsx:43-45` (`acceptShareHref` picks the route that exists in the
running bundle — web keeps `/accept-share/[token]`, the native export ships only the
`?token=` twin), `AcceptShare.tsx:91-120`, `AcceptShare.tsx:140-151`;
`ShareService.java:260-263` clears the token on accept.

**Verdict: PASS.**

---

### V-03a — An invite meant for someone else fails honestly

**As a** VSO signed in with the wrong address
**I want** to be told why it failed
**So that** I can fix it instead of guessing

- **Given** I am signed in as `other@vso.org`
- **When** I accept an invite created for `rep@vso.org`
- **Then** I see: "This invite was created for a different email address. Sign in with the email the veteran invited, or ask them for a new invite."
- **And** the share is not accepted

`AcceptShare.tsx:104-107`; `ShareService.java:251-256` → 403 `email_mismatch`.

**Verdict: PASS.**

---

### V-03b — A phone-only account can never accept

**As a** VSO who signed up with my phone number
**I want** to accept an invite
**So that** I can review the claim I was invited to

- **Given** I created my account with phone OTP and never attached an email
- **When** I accept any invite
- **Then** I should either succeed, or be told exactly what to do

**What actually happens:** every accept fails with 403 `email_mismatch`, forever. A
phone-only account's stored email is the synthetic placeholder
`<uid>@firebase.local` (`AuthController.java:121-127`), which matches no invited
address. `ShareService.java:251-256` compares against that stored value.

The error copy tells the viewer to "sign in with the email the veteran invited" —
but they have no email at all, and nothing points them at the fix.

**Verdict: GAP.** Given that this app ships **phone-or-email passwordless OTP** as
its only sign-in, an entire class of legitimate viewers hits a dead end with
misleading guidance. The fix is small — detect a synthetic email and route to the
"attach an email" OTP flow instead of showing mismatch copy — but the story fails
today. **This is the most likely cause of a previous local test producing nothing.**

---

### V-03c — Expired, revoked, or already-accepted links

**As a** VSO opening a stale link
**I want** to understand what happened
**So that** I know whether to ask for a new invite or just open the app

- **Given** a link that expired, was revoked, or that I already accepted
- **When** I open it
- **Then** I see "This invite is no longer active", an explanation that links last 7 days and work once, and an **Open After Duty** button in case I already have access
- **And** a malformed or unknown token instead says "This invite link doesn't work — double-check you copied the whole link"

`AcceptShare.tsx:20-34`, `AcceptShare.tsx:62-65`; backend 410s at
`ShareService.java:191-201`. Accepting twice returns 409, which the UI deliberately
treats as success (`AcceptShare.tsx:101-103`) — the same happy place for the viewer.

**Verdict: PASS.** Note the deliberate ambiguity: 410 cannot distinguish expired
from revoked from already-accepted, so the copy covers all three.

---

### V-04 — See which profiles are shared with me

**As a** VSO with clients
**I want** to see whose claims I can open
**So that** I can pick one

- **Given** I have accepted one or more shares
- **When** I am on my own claim
- **Then** I see a "Shared with you:" row offering "View {owner}'s claim" for each
- **And** revoked shares do not appear

`ViewerSwitcher.tsx:47-58`; `me.sharedProfiles` from `ShareService.listProfiles`
(`ShareService.java:281-317`), filtered to accepted and non-revoked; own claim
excluded at `AuthController.java:88-98`.

**Verdict: PASS.**

---

### V-05 — Open a shared profile

**As a** VSO
**I want** to switch into a veteran's claim
**So that** I can review it

- **Given** the switcher is showing
- **When** I choose "View Alice's claim"
- **Then** the whole app re-reads against Alice's claim
- **And** the selection survives navigation
- **And** if I somehow request a claim not shared with me, I get an honest failure rather than a wedged all-403 UI

`ViewerSwitcher.tsx:24-33` → `POST /api/view-as`, which validates the claim against
the caller's own `sharedProfiles` **before** setting the cookie
(`view-as/route.ts:42-45`), then full-navigates (a client transition would keep stale
viewer data in the RSC cache).

**Verdict: PASS.**

---

### V-06 — Always know whose claim I am in

**As a** VSO handling several claims
**I want** unmistakable, permanent indication of whose data I am looking at
**So that** I never confuse one veteran's record with another's — or with my own

- **Given** I am viewing a shared claim
- **When** I am on any page
- **Then** a banner above everything reads "Viewing **Alice's claim** — read-only" with an **Exit — back to my claim** button

`ViewerBanner.tsx:45-65`, rendered above the TopBar on every route
(`AppShell.tsx:108-114`).

**Verdict: PASS.** This is the safety rail that makes the rest of viewer mode
tolerable, so verify it appears on *every* page, not just Home.

---

### V-07 — Switch from one shared profile to another

**As a** VSO with two veterans' claims shared with me
**I want** to move from Alice's profile straight to Bob's
**So that** I can work a caseload without ceremony

- **Given** I am viewing Alice's claim
- **And** Bob's claim is also shared with me
- **When** I want to move to Bob's claim
- **Then** I can switch directly

**What actually happens:** the switcher is hidden the moment I am viewing anything.

```
AppShell.tsx:117   {!viewing && !!sharedClaims?.length && (<ViewerSwitcher … />)}
```

While `viewing` is true the `ViewerSwitcher` does not render at all. The only route
from Alice to Bob is: **Exit — back to my claim** → land on my own claim → pick Bob
from the switcher. Two full page navigations and a detour through my own data.

**Verdict: GAP.** This is the specific capability requested for this release ("the
person viewing should be able to switch between multiple profiles"). The mechanism
is sound — `POST /api/view-as` accepts any claim in `sharedProfiles` regardless of
the current selection, so a direct A→B switch already works at the API level. The
gap is purely the render condition on `AppShell.tsx:117` plus a sensible place to
put the switcher while the banner is showing.

---

### V-08 — Read the shared analysis

**As a** VSO with analysis access
**I want** to see the veteran's conditions, estimated ratings, evidence gaps and next steps
**So that** I can advise them on a real picture

- **Given** an accepted share with `canViewAnalysis`, and the owner on active Pro
- **When** I open Conditions, Next Steps, and Home
- **Then** I see Alice's analysis, not mine or an empty page

Every RSC read in the render carries `X-View-As` (`transport.ts:50-53`,
`transport.ts:74`); the backend resolves the shared claim and passes
`assertScope(VIEW_ANALYSIS)` (`ClaimAccessService.java:168-182`).

**Verdict: PASS.** The highest-value story in the document — test it thoroughly, and
cross-check a couple of specific conditions against what Alice sees on her own
screen.

---

### V-09a — See the document list

**As a** VSO
**I want** to see what records the veteran has filed
**So that** I know what evidence exists

- **Given** any accepted share (documents are always in scope — `VIEW_DOCS` always passes once access resolves, `ClaimAccessService.java:164-166`)
- **When** I open Documents
- **Then** I see Alice's documents: filenames, processing status, and extracted facts

**Verdict: PASS.**

---

### V-09b — Open an original document

**As a** VSO
**I want** to open or download the actual file
**So that** I can read the evidence rather than a summary of it

- **Given** I can see Alice's document list
- **When** I download or open one
- **Then** I get Alice's file

**What actually happens: 404.** The download route is a raw cookie→Bearer byte
passthrough that never reads the `cp_view_as` cookie and never sends `X-View-As`:

```
web/src/app/api/claim/evidence/[id]/download/route.ts:29-32
  fetch(`${API_BASE}/claim/evidence/${id}/download`, {
    headers: { Authorization: `Bearer ${token}` },   // no X-View-As
  })
```

The backend therefore resolves the **caller's own** claim
(`IntakeController.java:263`), looks up
`findByIdAndClaimId(evidenceId, vinceOwnClaimId)` (`IntakeController.java:265-266`),
finds nothing, and throws 404 "Evidence not found". The BFF surfaces
`{error:"download_failed"}`.

**Verdict: GAP.** "Original documents" is one of the three surfaces this release is
supposed to share, and the viewer can see that they exist but cannot open a single
one. The fix is confined to that one route — read `VIEW_AS_COOKIE` and forward it as
`X-View-As` on this GET, exactly as `serverFetch` does. The backend already handles
it correctly.

---

### V-10 — See the veteran's profile information

**As a** VSO
**I want** to see the veteran's service history — branch, periods of service, deployments
**So that** I can assess presumptive eligibility and service connection

- **Given** an accepted share
- **When** I open Profile while viewing Alice's claim
- **Then** I see Alice's service information

**What actually happens: I see my own profile.** `GET /auth/me` deliberately ignores
`X-View-As` and always returns the caller's own claim and profile
(`AuthController.java:77-79`). Service history is stored per **user**, not per claim
(`ServiceHistoryOverrideRepository.findByUserId`,
`ServiceHistoryResolutionRepository.findByUserId`), and there is no claim-scoped
endpoint that exposes it. `ProfileView.tsx` contains no viewer-mode handling at all.

**Verdict: GAP.** "Profile information" is the third of the three surfaces this
release is supposed to share, and none of it reaches the viewer. Worse, the page
shows *the viewer's own* profile with no indication it has fallen out of viewer
context — the banner still says "Viewing Alice's claim" above Vince's own service
record. That is actively misleading, and it is the one gap here with a
mistaken-identity risk rather than just a missing feature.

Note that this is a genuine design question, not only a bug: `/auth/me` ignoring
`X-View-As` is correct and deliberate for account-level data (X-03). Sharing the
veteran's *service history* needs a claim-scoped read, not a relaxation of
`/auth/me`.

---

### V-11 — I cannot see the veteran's AI conversations

**As a** VSO
**I want** the veteran's private AI chats to stay private
**So that** the trust that made them share with me is warranted

- **Given** Alice has an extensive chat history
- **When** I open Ask AI while viewing her claim
- **Then** I see an empty conversation — my own thread on her claim — never her messages

`ChatService.listMessages` is scoped to the caller's own `chat_threads` row and
returns empty when none exists (`ChatService.java:217-222`).

**Verdict: PASS.** The owner-side mirror of O-09.

Two things to expect while testing:

- **Ask AI is visible to viewers.** It is in both navs unconditionally
  (`Sidebar.tsx:13`, `MobileNav.tsx:14`) — see V-18.
- **Viewer chat needs `canViewAnalysis` AND the viewer's own Pro**
  (`ClaimAccessService.java:206-231`). Without either, expect 403
  `chat_requires_view_analysis` or `chat_requires_viewer_pro`. The asymmetry worth
  confirming is intended: analysis *reading* bills the owner's subscription, while
  viewer *chat* bills the viewer's.

---

### V-12 — A documents-only share tells me so

**As a** VSO given documents-only access
**I want** to be told the analysis is not shared
**So that** I do not read an empty Conditions page as "this veteran has no conditions"

- **Given** a share with `canViewAnalysis = false`
- **When** I am viewing the claim
- **Then** the banner adds: "This share includes documents only — the analysis (conditions, next steps, estimates) isn't shared with you."

`ViewerBanner.tsx:33-37`.

**Verdict: PASS.** An empty page that lies is worse than a blocked one that explains
— verify the notice actually appears rather than a bare empty state.

---

### V-13 — When the veteran's Pro lapses, I am told the truth

**As a** VSO whose client's subscription ended
**I want** to know why the analysis vanished
**So that** I do not report a bug, or assume the veteran deleted their data

- **Given** a share granting analysis, where the owner's Pro has lapsed
- **When** I am viewing the claim
- **Then** the banner reads: "{Owner} needs an active Pro subscription for analysis sharing — documents are still available."
- **And** a genuine upstream outage is **not** blamed on the owner's plan

`ViewerBanner.tsx:38-43`; `probeAnalysisBlocked` (`endpoints-core.ts:264-267`) maps
only 403/402 to `blocked`, and `layout.tsx:63-70` treats any other failure as an
honest unknown.

**Verdict: PASS.**

---

### V-14 — Get back to my own claim

**As a** VSO
**I want** to leave viewer mode
**So that** I return to my own account

- **Given** I am viewing a shared claim
- **When** I press **Exit — back to my claim**
- **Then** the selection is cleared and I land on my own claim
- **And** no stale viewer data survives the transition

`ViewerBanner.tsx:22-30` → `DELETE /api/view-as` then a **full** navigation — a
client-side transition would keep stale viewer data in the RSC cache.

**Verdict: PASS.**

---

### V-15 — Revoked access ends cleanly, mid-session

**As a** VSO whose access was just revoked
**I want** a clean landing rather than a wall of errors
**So that** I understand what happened

- **Given** I am viewing Alice's claim
- **When** Alice revokes my access and I navigate or refresh
- **Then** the stale selection is cleared server-side and I land on my own claim
- **And** no read ever runs against a claim I can no longer access

`layout.tsx:47-55` — the selection is checked against live `sharedProfiles` on every
render and redirected to `/api/view-as/exit` when it no longer matches.

**Verdict: PASS.** Test this one live: revoke from Alice's browser while Vince has
the claim open, then refresh Vince.

**Worth noting while testing:** the landing is clean but silent — Vince is simply
back on his own claim with no explanation. Whether that deserves a message is a
product call, not a defect.

---

### V-16 — A selection does not linger

**As a** VSO on a shared computer
**I want** my viewer selection to expire
**So that** walking away does not leave a veteran's claim on screen

- **Given** I entered viewer mode
- **When** four hours pass
- **Then** the selection expires and I am back on my own claim

`view-as/route.ts:18` — `MAX_AGE = 60 * 60 * 4`, httpOnly, `sameSite: lax`, `secure`
in production.

**Verdict: PASS.** Not practical to test by waiting; verify the cookie's `Max-Age` in
devtools instead.

---

### V-17 — Add a document on the veteran's behalf

**As a** VSO granted upload permission
**I want** to add a record to the veteran's claim
**So that** I can complete their file for them

- **Given** a share with `canUploadDocs = true`
- **When** I upload a document while viewing Alice's claim
- **Then** the document is added to **Alice's** claim

**What actually happens: the file lands on my own claim, silently.**

Two independent layers strip the context, and neither reports an error:

1. `withAmbientViewAs` removes `viewAs` from every non-GET request
   (`transport.ts:43-49`) — deliberate, documented as P1-19 defense in depth.
2. `web/src/app/api/upload/route.ts:22-27` is a raw multipart passthrough that never
   reads the cookie in the first place.

The backend receives no `X-View-As`, resolves Vince's own claim, and stores the
document there. The upload **succeeds** — with a 200 — against the wrong claim. Alice
never sees the file; Vince finds a stray document in his own records.

Meanwhile the share model fully supports this: `canUploadDocs` is honored by
`assertScope(UPLOAD_DOCS)` (`ClaimAccessService.java:184-191`), and the invite
preview promises "Add documents on their behalf" (`AcceptShare.tsx:204-209`).

**Verdict: GAP.** The owner grants a permission the app cannot honor, and the failure
mode is silent misrouting rather than a refusal. Note that the viewer banner already
promises "read-only", so the honest minimum fix is to stop offering the permission at
all; the full fix is a view-as-aware upload lane.

---

### V-18 — Viewer mode does not offer me things I cannot do

**As a** VSO in read-only mode
**I want** the app to stop showing me buttons that do not work
**So that** I do not corrupt anything or waste time

- **Given** I am viewing a shared claim
- **When** I look at any page
- **Then** mutation affordances — Add evidence, upload, delete, the chat composer — are hidden or disabled

**What actually happens: nothing is hidden, except one link.**

`ViewerContext` was built exactly for this. Its own comment says "ANY downstream
client component can ask `useViewer().viewing` and hide its mutation affordances"
(`ViewerContext.tsx:3-7`). **It has zero production consumers** — the only references
outside the file itself are a test and a type comment. `AppShell.tsx:99` sets
`data-viewer="1"` on the root element, and no CSS rule anywhere reads it.

The single exception is the sidebar's "Add evidence" link, hidden via a prop
(`Sidebar.tsx:45`). Everything else — the Documents upload card, delete controls, the
Ask AI tab in both navs — renders unchanged.

**Verdict: GAP.** On its own this is a polish issue: the backend enforces every scope
regardless, so nothing here is a security hole. It matters because it is what turns
V-17 from a hidden API quirk into something a real VSO will actually do — the upload
card is right there, it appears to work, and the file goes to the wrong claim.

---

## 5. Cross-cutting — boundaries that must hold

These are the "does the fence hold" checks. All four are PASS by construction; verify
them anyway, because they are the ones that matter if they ever break.

### X-01 — I cannot reach a claim nobody shared with me

- **Given** I am authenticated with no share on claim 999
- **When** I request `POST /api/view-as {claimId: 999}`, or hit the API with `X-View-As: 999`
- **Then** I get 403 at both layers

BFF pre-check: `view-as/route.ts:42-45` (`no_share_access`).
Backend: `ClaimAccessService.java:126-135` — a non-owner needs an accepted,
non-revoked share or it throws 403.

**Verdict: PASS.**

---

### X-02 — A different account on the same browser inherits nothing

- **Given** Vince viewed Alice's claim, then signed out, and Alice signs in on the same browser
- **When** the app renders
- **Then** the stale `cp_view_as` cookie is cleared rather than honored

`layout.tsx:48-55` — the selection is validated against the *current* user's
`sharedProfiles` on every render.

**Verdict: PASS.**

---

### X-03 — Account-level actions ignore viewer mode

- **Given** I am viewing Alice's claim
- **When** account-level endpoints are called
- **Then** they act on **my** account, never Alice's — a VSO must never rename the veteran

`AuthController.java:77-79` (`getMe`), `AuthController.java:130-136` (`patchMe`),
`AuthController.java:245-248` (corrections), `IntakeController.java:583`
(admin-only paths).

**Verdict: PASS.** Correct and deliberate — and, as V-10 notes, the reason sharing
profile information needs its own claim-scoped read rather than a change here.

---

### X-04 — A viewer can never destroy evidence

- **Given** a share with `canUploadDocs = true` (the most permissive share available)
- **When** I try to delete one of the veteran's documents
- **Then** I get 403 `delete_docs_owner_only`

`ClaimAccessService.java:193-204` — `DELETE_DOCS` is split out of the upload scope
precisely so that adding evidence never implies destroying it.

**Verdict: PASS.** The right call, and worth a deliberate attempt to break.

---

## 6. Release gate

Eight stories fail. Grouped by what they cost.

### Blocks the stated release goal

The release promises the viewer gets "AI analysis, original documents, profile
information". One of those three works.

| ID | Gap | Cause | Fix size |
|---|---|---|---|
| **V-09b** | Viewer cannot open any original document | `download/route.ts:29-32` never forwards `X-View-As` | One route — read the cookie, forward the header |
| **V-10** | Viewer sees their own profile, not the veteran's | `/auth/me` ignores `X-View-As` (correctly); service history is user-scoped with no claim-scoped read | New claim-scoped endpoint + viewer-aware ProfileView |
| **V-07** | Viewer cannot switch A→B without exiting first | Render condition `!viewing` on `AppShell.tsx:117` | The condition, plus placement while the banner shows |

### Blocks anyone testing it at all

| ID | Gap | Cause | Fix size |
|---|---|---|---|
| **V-03b** | Phone-only accounts can never accept an invite, and the error misleads | Synthetic `@firebase.local` email can never match | Detect synthetic email → route to attach-email OTP flow |

### Correctness risk, not scope

| ID | Gap | Cause |
|---|---|---|
| **V-17** | Viewer upload silently lands on the viewer's own claim | `transport.ts:43-49` strips writes; `upload/route.ts` never sends the header |
| **V-18** | No mutation affordance is hidden in viewer mode | `useViewer()` has zero consumers; `data-viewer` has no CSS |

### Polish

| ID | Gap |
|---|---|
| **O-07** | No UI to change an existing share's permissions (backend PATCH exists, unused) |
| **O-11** | No "no one has access" confirmation on an empty share list |

---

## 7. Decisions needed from the owner

Two of these are product calls, not defects, and they set the size of the release.
Answer inline.

<!-- OWNER CALL — V-07, direct profile switching -->
**V-07 — direct A→B profile switching.** You named this as a requirement for this
release. Today it costs an Exit and a detour through your own claim. Options:
**blocks release** (fix now — it is a render condition and a placement decision),
**ships as-is** (Exit-then-switch is acceptable for v1), or **ships, tracked**.

> **Decision:**
>
> _(your call here)_

<!-- OWNER CALL — V-17/V-18, viewer document upload -->
**V-17 + V-18 — viewer document upload.** The owner can grant it, the invite promises
it, the backend honors it, and the web app silently files the document on the wrong
claim. Options: **blocks release** (build a view-as-aware upload lane), **ships
honestly** (remove the "Allow document uploads" toggle and the invite-preview promise,
keep viewer mode strictly read-only as the banner already claims — smallest change, no
lie left in the product), or **ships, tracked** (not recommended — the affordance is
visible and silently wrong).

> **Decision:**
>
> _(your call here)_

---

## 8. How to run this

1. Work through §1 and confirm every pre-flight item. Most failed local runs die here.
2. Run the Owner stories in order as Alice. Create **two** invites — one with analysis, one without.
3. Deliver both links by copying them. Accept as Vince (analysis) and a second viewer address (documents-only).
4. Have Bob share his claim with Vince as well — V-07 needs two shared profiles.
5. Run the Viewer stories as Vince, switching between Alice and Bob.
6. Run §5 deliberately trying to break the boundaries.
7. Record each verdict as confirmed or contradicted. **A PASS that fails in practice is the highest-value finding in this document** — it means the code says one thing and the running system does another.
