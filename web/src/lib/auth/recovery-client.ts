"use client";

// Client transport for dual-channel factor-2 RECOVERY (auth program P1.5 — the
// pinned C contract). Recovery lets a veteran who can no longer use their passkey
// / Face ID back in by re-proving possession of BOTH original channels (a fresh
// emailed code AND a fresh phone verification), after which the backend clears
// every saved factor-2 credential so they re-enroll cleanly.
//
// This module owns ONLY the two backend calls + their {detail}/{code} error
// shapes. The phone verification ceremony (Firebase Phone Auth) and the
// custom-token session establishment are the driver's job (same helpers the login
// page / StepUpModal already use), so recovery reuses the proven seams:
//   start  → POST /api/auth/recovery/start  {identifier}         → always {ok:true}
//   verify → POST /api/auth/recovery/verify {email, emailCode, phoneIdToken}
//            → 200 {custom_token, revokedFactors}
//            | 400 {detail}  (bad email code / bad phone proof — generic)
//            | 409 {code:"recovery_needs_support"}  (single-channel legacy account)
//
// Target-agnostic (no BFF/plugin imports): web posts to the same-origin BFF proxy
// routes; native could post to Spring directly with the same bodies.

const RECOVERY_BASE = "/api/auth/recovery";

/** The stable 409 code the backend returns for a single-channel legacy account
 *  that cannot satisfy the dual-channel proof — the veteran needs human support. */
export const RECOVERY_NEEDS_SUPPORT = "recovery_needs_support";

/** A recovery call failed. `needsSupport` is the single-channel 409 (route the
 *  veteran to the support-hold path); otherwise `detail` is user-facing copy. */
export class RecoveryError extends Error {
  constructor(
    public readonly status: number,
    /** The backend's user-facing {detail}, when present. */
    public readonly detail: string | null,
    /** True iff this is the 409 {code:"recovery_needs_support"}. */
    public readonly needsSupport: boolean = false,
  ) {
    super(detail ?? (needsSupport ? RECOVERY_NEEDS_SUPPORT : `recovery ${status}`));
    this.name = "RecoveryError";
  }
}

/** The verify endpoint's response. `custom_token` is exchanged for a session by
 *  the driver; `revokedFactors` tells the UI what was cleared so the "set up Face
 *  ID again" copy is honest. */
export interface RecoveryVerifyResponse {
  custom_token: string;
  revokedFactors: string[];
}

async function recoveryPost<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${RECOVERY_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let detail: string | null = null;
    let code: string | null = null;
    try {
      const j = await res.json();
      if (j?.detail) detail = String(j.detail);
      if (j?.code) code = String(j.code);
    } catch {
      /* non-JSON body */
    }
    throw new RecoveryError(res.status, detail, code === RECOVERY_NEEDS_SUPPORT);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

/** Begin recovery for an identifier (email or phone). ALWAYS resolves (the backend
 *  is anti-enumeration — it returns 200 whether or not the account exists), so the
 *  UI must NEVER treat this as confirmation the account is real. */
export async function startRecovery(identifier: string): Promise<void> {
  await recoveryPost<{ ok: boolean }>("/start", { identifier });
}

/** Complete recovery with BOTH proofs. Throws `RecoveryError` on any factor
 *  failure (generic 400) or the single-channel 409 (`needsSupport`). Returns the
 *  custom token + the list of factor families that were revoked. */
export async function verifyRecovery(
  email: string,
  emailCode: string,
  phoneIdToken: string,
): Promise<RecoveryVerifyResponse> {
  return recoveryPost<RecoveryVerifyResponse>("/verify", { email, emailCode, phoneIdToken });
}
