// Single-box "Phone or email" identifier handling for passwordless OTP sign-in
// (passwordless-otp-auth-spec §9.2). Ported verbatim from VolunTails so the two
// apps route identifiers identically.

export type Classified =
  | { kind: "email"; email: string }
  | { kind: "phone"; e164: string }
  | { kind: "invalid" };

// Detect EMAIL intent (any "@") BEFORE phone normalization. Stripping non-digits
// from an email that contains a 10/11-digit run (user1234567890@example.com,
// name.5551234567@gmail.com) would mis-route it to phone, hit Firebase Phone
// Auth, fail auth/invalid-phone-number, and permanently lock out a user with a
// perfectly valid email. So: any value containing "@" → email; only a "@"-free
// value is tried as a phone. Genuinely malformed addresses are caught downstream
// (the backend's shape check / Firebase).
export function classify(raw: string): Classified {
  const v = (raw || "").trim();
  if (v.includes("@")) return { kind: "email", email: v.toLowerCase() };
  const e164 = toE164US(v);
  return e164 ? { kind: "phone", e164 } : { kind: "invalid" };
}

// US phone → E.164 (the owner's preference: accept 10 digits, auto-prepend +1).
//   • already "+"        → keep iff 8–15 digits
//   • 10 digits          → +1XXXXXXXXXX
//   • 11 digits, lead 1  → +1XXXXXXXXXX
//   • otherwise          → null
export function toE164US(raw: string): string | null {
  const trimmed = (raw || "").trim();
  const d = trimmed.replace(/\D/g, "");
  if (trimmed.startsWith("+")) return d.length >= 8 && d.length <= 15 ? "+" + d : null;
  if (d.length === 10) return "+1" + d;
  if (d.length === 11 && d.startsWith("1")) return "+" + d;
  return null;
}
