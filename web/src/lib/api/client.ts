import "server-only";
import { cookies } from "next/headers";
import { SESSION_COOKIE } from "@/lib/constants";
import { UnauthorizedError } from "./errors";
import {
  VIEW_AS_COOKIE,
  buildHeaders,
  mapResponse,
  withAmbientViewAs,
  type ApiFetch,
  type FetchOpts,
} from "./transport";

export type { FetchOpts } from "./transport";

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

/**
 * The single BFF egress point. Reads the Firebase ID token from the httpOnly
 * cookie and forwards it as a Bearer to Spring, server-to-server.
 *
 * SECURITY: outbound headers are an ALLOWLIST — Authorization (+ optional
 * X-View-As) only. We never forward inbound client headers, and never emit
 * X-User-Email (Spring honors that only in local dev-mode).
 *
 * This is the web implementation of the `ApiFetch` transport seam
 * (capacitor-ios-spec §A.2): the header allowlist and status→error mapping live
 * in `transport.ts`; this wrapper supplies the cookie token + base URL +
 * `cache: "no-store"`. Behavior is byte-for-byte identical to the inlined
 * version it replaces.
 */
export const serverFetch: ApiFetch = async <T>(path: string, opts: FetchOpts = {}): Promise<T> => {
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  if (!token) throw new UnauthorizedError();

  // Viewer-mode selection (P0-8): the cp_view_as cookie carries the shared
  // claim id; `withAmbientViewAs` forwards it as X-View-As on GETs ONLY and
  // strips any viewAs from mutations (read-only viewer mode).
  const rawViewAs = jar.get(VIEW_AS_COOKIE)?.value;
  const ambient = rawViewAs && /^\d+$/.test(rawViewAs) ? Number(rawViewAs) : null;
  const eff = withAmbientViewAs(opts, ambient);

  const res = await fetch(`${API_BASE}${path}`, {
    method: eff.method ?? "GET",
    headers: buildHeaders(token, eff),
    body: eff.body !== undefined ? JSON.stringify(eff.body) : undefined,
    cache: "no-store",
  });

  return mapResponse<T>(res, path, eff);
};
