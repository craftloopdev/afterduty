// Build-time platform constant (capacitor-ios-spec §A.3). `NEXT_PUBLIC_NATIVE`
// is "1" only in the Capacitor export build (`build:native`); on the web target
// it is unset, so `NATIVE` folds to `false` and the bundler eliminates every
// `if (NATIVE)` native branch from the web output. CAP-1 introduces the constant
// and the link helpers; no Capacitor code consumes them yet.
export const NATIVE = process.env.NEXT_PUBLIC_NATIVE === "1";

/**
 * Canonical href for a condition's detail page. On web the dynamic
 * `/conditions/[id]` route stays primary; on native (static export) the same
 * destination is reached through the query-param twin `/conditions/detail?id=`
 * (§A.3 — `output:"export"` cannot pre-render per-user dynamic params). One
 * decision point so internal links never have to branch inline.
 */
export function condHref(id: number | string): string {
  return NATIVE ? `/conditions/detail?id=${id}` : `/conditions/${id}`;
}

/**
 * Canonical href for accepting a share invite. Web keeps `/accept-share/[token]`
 * canonical; native uses the twin `/accept-share?token=` (§A.3). The web origin
 * form (used in copied invite links and email deep-links) stays the dynamic
 * path — it must resolve for any recipient on the public web.
 */
export function acceptShareHref(token: string): string {
  return NATIVE ? `/accept-share?token=${token}` : `/accept-share/${token}`;
}
