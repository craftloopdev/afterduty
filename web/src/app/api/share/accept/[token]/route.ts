import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";
import type { ShareDto, SharePreviewDto } from "@/lib/models/api";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 500;
  return NextResponse.json({ error: "upstream" }, { status });
}

/** Preview a share invite (404 invalid, 410 expired/revoked/used). */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ token: string }> },
) {
  const { token } = await params;
  try {
    const res = await serverFetch<SharePreviewDto>(`/shares/accept/${encodeURIComponent(token)}`);
    return NextResponse.json(res ?? {});
  } catch (e) {
    return mapError(e);
  }
}

/** Accept a share invite as the signed-in viewer (403 email mismatch, 409 already accepted). */
export async function POST(
  _request: Request,
  { params }: { params: Promise<{ token: string }> },
) {
  const { token } = await params;
  try {
    const res = await serverFetch<ShareDto>(`/shares/accept/${encodeURIComponent(token)}`, {
      method: "POST",
    });
    return NextResponse.json(res ?? { ok: true });
  } catch (e) {
    return mapError(e);
  }
}
