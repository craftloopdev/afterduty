// Deep-link path mapper (capacitor-ios-spec §D.3/§B.5/§F.3). Maps an inbound
// universal-link (https://app.afterduty.app/…) or custom-scheme
// (afterduty://…) URL to the in-app route to navigate to. Pure + unit-testable;
// the wiring (App.addListener("appUrlOpen", …)) lives in `installDeepLinks`.
//
// Native uses the query-param twin routes (§A.3a), so the share universal-link
// `/accept-share/{token}` maps to `/accept-share?token=…`. Unknown paths fall
// home. Capture builds extend the allowlist to `/dev/*` for the screenshot
// pipeline (§F.3). (The former `/finish-sign-in` mapping left with the
// magic-link flow — OTP sign-in has no inbound link.)

export interface DeepLinkOptions {
  /** Capture builds allow `/dev/*` deep links for the screenshot pipeline. */
  allowDev?: boolean;
}

// In-app destinations that are reachable BEFORE auth resolves (§B.5/§A.3a). The
// share-accept preview is a public GET on Spring that runs pre-sign-in, so the
// native auth gate must NOT redirect it to /login on a cold launch — otherwise
// a deep link that lands while `status === "signed-out"` races (and can lose
// to) the gate's `/login` replace, dropping the inbound token (dim 6). The
// mapper already emits the twin form; this is the single source of truth for
// "don't clobber this destination".
export const PUBLIC_PREFIXES = ["/accept-share"] as const;

/** True when an in-app destination (path, optionally with query) is one of the
 *  pre-auth public flows — used by the native gate to avoid clobbering a cold
 *  deep link with its signed-out `/login` redirect. */
export function isPublicDest(dest: string): boolean {
  const path = dest.split("?")[0];
  return PUBLIC_PREFIXES.some((p) => path === p || path.startsWith(`${p}/`));
}

/** Translate a deep-link URL into the in-app path (with query) to navigate to,
 *  or `null` to ignore. */
export function mapDeepLink(rawUrl: string, opts: DeepLinkOptions = {}): string | null {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    return null;
  }

  // Custom-scheme URLs (afterduty://…) have no authority, and engines disagree
  // on how to expose their path: Node/WebKit give "/dev/x" for
  // "afterduty:///dev/x", but the Android WebView (Chromium) treats the URL as
  // opaque and returns the whole remainder — "///dev/x" — so a plain
  // startsWith("/dev/") silently fails on-device (2026-09-14: every screenshot
  // was the login screen). Collapse any run of leading slashes to one; https
  // universal links have a host and are unaffected.
  const path = url.pathname.replace(/^\/{2,}/, "/").replace(/\/+$/, "") || "/";
  const search = url.search; // includes leading "?" or ""

  // Share-invite acceptance: universal link `/accept-share/{token}` →
  // query-param twin `/accept-share?token=…` (§A.3a). Also accept the already-
  // twinned `?token=` form.
  const acceptMatch = path.match(/^\/accept-share\/([^/]+)$/);
  if (acceptMatch) {
    return `/accept-share?token=${encodeURIComponent(decodeURIComponent(acceptMatch[1]))}`;
  }
  if (path === "/accept-share") {
    return `/accept-share${search}`;
  }

  // Condition detail deep link → query-param twin (§A.3a).
  const condMatch = path.match(/^\/conditions\/(\d+)$/);
  if (condMatch) {
    return `/conditions/detail?id=${condMatch[1]}`;
  }

  // Screenshot-pipeline dev routes (capture builds only — §F.3).
  if (opts.allowDev && path.startsWith("/dev/")) {
    return `${path}${search}`;
  }

  // Custom-scheme reopen bridge (afterduty://signed-in) and anything else → home.
  return "/";
}
