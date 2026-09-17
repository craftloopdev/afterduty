/** The httpOnly cookie that carries the Firebase ID token to the BFF. */
export const SESSION_COOKIE = "cp_session";

/** Appearance prefs cookie (theme/scale/dark) — read server-side for no-FOUC SSR. */
export const PREFS_COOKIE = "cp_prefs";

/** Routes reachable without a session (login + share-invite accept + dev previews). */
export const PUBLIC_PREFIXES = ["/login", "/accept-share", "/dev"];

// Canonical hosted legal URLs (capacitor-ios-spec §C.3/§H.6.3). Single source of
// truth so the web auth footer (LegalFooter) and the native paywall's
// Terms/Privacy links can NEVER drift — drift here is the literal 3.1.2(c) fix
// that the v1.0 rejection demanded. The Terms link is Apple's Standard EULA (the
// license the app ships under); the Privacy Policy is the standalone hosted page.
// These same URLs also appear in the ASC Privacy field (the NEW After Duty
// listing, Stage 2 of the rebrand), the App Store Description, and the review
// notes — keep all five identical. The canonical privacy page is served by the
// afterduty.app marketing site; the in-app copy at /privacy.html remains as an
// offline-capable mirror.
export const EULA_URL = "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/";
export const PRIVACY_URL = "https://afterduty.app/privacy";
