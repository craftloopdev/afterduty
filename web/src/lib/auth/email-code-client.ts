"use client";

// Shared transport for the email-code passwordless flow
// (passwordless-otp-auth-spec §1, §9). The request/verify/attach bodies and the
// FastAPI-style {detail} error shape are IDENTICAL on both targets; only the
// URL base and auth differ, so the drivers own those:
//   - web    → the same-origin BFF proxy routes (app/api/auth/email-code/*),
//              which forward the client IP (request/verify) or convert the
//              session cookie → Bearer (attach).
//   - native → Spring directly (`${apiBase()}/auth/email-code/*`), passing the
//              keychain ID token as an explicit Bearer for attach.
// The backend's {detail} is already user-facing copy; we surface it via
// EmailCodeError.detail. This module is target-agnostic (no BFF/plugin imports)
// so the login page can `instanceof` the error on either bundle.

export class EmailCodeError extends Error {
  constructor(
    public readonly status: number,
    /** The backend's user-facing {detail} message, when present. */
    public readonly detail: string | null,
  ) {
    super(detail ?? `email-code ${status}`);
    this.name = "EmailCodeError";
  }
}

export type Purpose = "signin" | "attach";

/** The verify endpoint's response (spec §2.2). `custom_token` stays inside the
 *  drivers — the login flow only sees the dual-verify signals. */
export interface VerifyResponse {
  custom_token: string;
  isNewUser: boolean;
  hasPhone: boolean;
}

/** POST an email-code call and map the {detail} error shape. `bearer` is only
 *  set by the native driver's attach (the web BFF converts the cookie itself). */
export async function emailCodePost<T>(url: string, body: unknown, bearer?: string): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (bearer) headers.Authorization = `Bearer ${bearer}`;
  const res = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let detail: string | null = null;
    try {
      const j = await res.json();
      if (j?.detail) detail = String(j.detail);
    } catch {
      /* non-JSON body */
    }
    throw new EmailCodeError(res.status, detail);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}
