import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/constants";

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

// Headers we pass through from Spring's download response. Spring already
// allowlists the Content-Type (stored-XSS hardening) and sets an attachment
// Content-Disposition + nosniff; we forward exactly those and nothing else.
const PASS_HEADERS = ["content-type", "content-disposition", "x-content-type-options", "content-length"];

/**
 * Stream the original uploaded file back to the browser (P1-29 Download).
 * This is a byte passthrough, not a JSON call, so it uses the same raw
 * cookie→Bearer pattern as /api/upload rather than serverFetch.
 */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }

  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return NextResponse.json({ error: "unauthorized" }, { status: 401 });

  const res = await fetch(`${API_BASE}/claim/evidence/${id}/download`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });

  if (!res.ok) {
    // 401/404/… pass through as JSON — never stream an upstream error body.
    return NextResponse.json({ error: "download_failed" }, { status: res.status });
  }

  const headers = new Headers();
  for (const h of PASS_HEADERS) {
    const v = res.headers.get(h);
    if (v) headers.set(h, v);
  }
  return new NextResponse(res.body, { status: 200, headers });
}
