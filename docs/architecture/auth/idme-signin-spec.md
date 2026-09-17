# VAClaimPath — ID.me Sign-In Spec

**Status:** Design spec for implementation (hand-off to Claude Code)
**What ID.me is here:** an OpenID Connect (OIDC) / OAuth 2.0 **identity provider**. It performs IAL2 identity proofing and its own MFA. It is **optional** — offered alongside the phone/email path, never forced.

> **Boundary:** ID.me proves *who the user is*. It does **not** deliver VA records. Pulling VA data happens through VA's **Lighthouse** OAuth server (where ID.me is one of the sign-in choices). See `lighthouse-import-feature-request.md`. Do not implement "import VA data" against ID.me directly.

> Items marked `[VERIFY]` must be confirmed in the ID.me developer console / current docs.

---

## 1. Scenario A — ID.me as a login button

Standard OIDC **authorization code flow + PKCE**.

1. User taps **`Continue with ID.me`**.
2. App/redirect → ID.me `/authorize` with: `client_id`, `redirect_uri`, `response_type=code`, `scope`, `state`, `nonce`, PKCE `code_challenge` (+ `code_challenge_method=S256`).
3. User authenticates and (if needed) completes identity proofing + MFA **at ID.me**.
4. ID.me redirects back with `code` (+ `state`).
5. Backend exchanges `code` at the token endpoint using `code_verifier` → `id_token` + `access_token`.
6. Validate `id_token`: signature via ID.me JWKS, plus `iss`, `aud`, `exp`, and `nonce` match.
7. Establish app session; provision or link the user record keyed on the ID.me subject (`sub`).

**No separate app-side second factor is required on this path** — ID.me already satisfies IAL2 + MFA. Record that in the factor-mapping audit note.

### Redirect URI handling
- **Native:** use PKCE with an app-claimed callback — Universal Links (iOS) / App Links (Android), or a custom scheme. Run the code→token exchange **server-side**, not in the client. `[VERIFY]` allowed redirect URIs registered with ID.me.
- **Web:** server-side authorization-code exchange; session cookie is httpOnly + Secure + SameSite.

### Scopes
- Base: `openid`, `profile`, `email`.
- Veteran/military affiliation verification scope for confirming service status. **`[VERIFY]` exact scope identifiers** — do not hardcode a guessed string; pull the real values from the ID.me developer console.

---

## 2. Scenario B — link ID.me to an existing phone/email account

For a user who signed up via OTP + biometric/passkey (no ID.me yet) and now wants to (a) use ID.me for future one-tap login and/or (b) import VA data.

1. User is already authenticated locally in the app.
2. User taps **`Connect ID.me`** (or **`Import my VA records`**, which triggers this if not yet linked).
3. Run the **same OIDC + PKCE flow** as Scenario A, but as an **account-linking** step:
   - On success, store the mapping `idme_sub → existing app user_id`.
   - **Do not create a duplicate account.** If the ID.me `sub` (or verified email) already maps to a different app user, surface a merge/conflict path rather than silently forking.
4. After linking, the user can log in via either method.

### Relationship to VA data import
- Linking ID.me to the app account is about **app login**, not data retrieval.
- The actual **VA data import** runs through the Lighthouse OAuth flow, where the veteran signs in with ID.me (or Login.gov) at VA's page and VA issues the data-access tokens. The two flows are independent; linking is convenience, the Lighthouse authorization is what authorizes record access. See `lighthouse-import-feature-request.md`.

---

## 3. Security notes
- Always PKCE; always validate `state` (CSRF) and `nonce` (replay).
- Token exchange server-side only; never expose the client secret to the client.
- Store ID.me tokens/refresh material in the BAA-covered backend, encrypted; honor revocation and logout.
- Session lifetime and step-up rules follow `auth-security-flows.md` §4 and §6.

---

## 4. Verification checklist before coding
- [ ] Register client, redirect URIs (native + web), and confirm environments (sandbox/prod) `[VERIFY]`
- [ ] Confirm exact scope identifiers, including the veteran/military verification scope `[VERIFY]`
- [ ] Confirm current OIDC endpoints + JWKS URL `[VERIFY]`
- [ ] Define the account-link conflict/merge path
- [ ] Confirm ID.me is still an accepted VA sign-in CSP for the Lighthouse flow (see feature request) `[VERIFY]`
