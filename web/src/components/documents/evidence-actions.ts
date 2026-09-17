// Per-document evidence actions (P1-29/P1-30) — a local transport seam that
// mirrors the `mutations.ts` facade pattern (capacitor-ios-spec §A.5): the WEB
// branch hits the BFF route handlers under /api/claim/evidence/**, the NATIVE
// branch (build-time constant, dead branch tree-shaken on web) calls Spring
// directly with a Bearer ID token. Both return the raw `Response` so callers
// keep identical status branching on both targets.

import { NATIVE } from "@/lib/platform";
import { apiBase, bearer } from "@/lib/api/direct";

/** Authed raw-`Response` call to Spring for the native branch (mirrors the
 *  BFF's outbound allowlist: Authorization + caller-supplied Accept). */
async function direct(
  path: string,
  init: { method?: string; headers?: Record<string, string> } = {},
): Promise<Response> {
  const token = await bearer();
  return fetch(`${apiBase()}${path}`, {
    method: init.method ?? "GET",
    headers: { Authorization: `Bearer ${token}`, ...(init.headers ?? {}) },
  });
}

/** DELETE a document. Spring removes the evidence + its extracted facts and
 *  clears the synthesis/gap timestamps so the pipeline re-runs (204). */
export function deleteEvidence(id: number): Promise<Response> {
  if (NATIVE) return direct(`/claim/evidence/${id}`, { method: "DELETE" });
  return fetch(`/api/claim/evidence/${id}`, { method: "DELETE" });
}

/** GET the LIVE extracted facts for one document (AtomDto[] on 200). */
export function fetchEvidenceFacts(id: number): Promise<Response> {
  if (NATIVE) return direct(`/claim/evidence/${id}/facts`, { headers: { Accept: "application/json" } });
  return fetch(`/api/claim/evidence/${id}/facts`);
}

/**
 * Same-origin download href for the original file (web only — the BFF streams
 * Spring's Content-Disposition attachment through). Native has no BFF and an
 * anchor can't carry a Bearer token, so callers must not render a download
 * link when NATIVE (needs @capacitor/filesystem + share — not installed yet).
 */
export function evidenceDownloadHref(id: number): string {
  return `/api/claim/evidence/${id}/download`;
}
