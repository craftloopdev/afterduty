export const GENERIC_ERROR = "Something went wrong. Please try again.";

// Veterans who turned on two-step verification in the mobile app hit a real
// dead-end here: the full web MFA challenge isn't built yet. Be honest about
// why and point them at the path we KNOW works (the mobile app). We don't
// promise a specific web sign-in method as an escape hatch: phone may itself be
// the enrolled second factor, and the email-link/phone paths don't surface
// `auth/multi-factor-auth-required` the way the OAuth/password resolver does,
// so this copy only reliably fires on the popup path anyway.
// P2-4: "contact support" used to promise a channel that existed nowhere in
// the product. Name the real address (the same one the paywall's hardship note
// links) so the fallback is actually reachable.
export const SUPPORT_EMAIL = "support@afterduty.app";

export const MFA_MESSAGE =
  "Your account uses two-step verification, which isn't supported on the web yet. " +
  "Please open After Duty on your phone to sign in. " +
  `If you can't, email us at ${SUPPORT_EMAIL} and we'll help you get in.`;

/**
 * Map a Firebase auth error to plain, veteran-friendly copy. The fallback is a
 * fixed generic string — we never surface a raw Firebase `error.message`, which
 * leaks internals and breaks the page's plain-English voice.
 */
export function friendlyError(e: unknown): string {
  const code = (e as { code?: string })?.code ?? "";
  // auth/multi-factor-auth-required — enrolled user, no web resolver yet.
  if (code.includes("multi-factor")) return MFA_MESSAGE;
  if (code.includes("popup-closed") || code.includes("cancelled")) return "Sign-in was cancelled.";
  if (code.includes("popup-blocked"))
    return "Your browser blocked the sign-in window. Allow pop-ups for this site and try again.";
  if (code.includes("network")) return "Network error — please check your connection.";
  if (code.includes("invalid-verification-code")) return "That code didn't match. Try again.";
  if (code.includes("invalid-phone")) return "That phone number doesn't look right. Try again.";
  if (code.includes("too-many-requests")) return "Too many attempts. Please wait and try again.";
  return GENERIC_ERROR;
}
