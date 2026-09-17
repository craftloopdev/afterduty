import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 500;
  return NextResponse.json({ error: "upstream" }, { status });
}

/** Create a share invite. */
export async function POST(request: Request) {
  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) ?? {};
  } catch {
    body = {};
  }
  const viewerEmail = String(body.viewerEmail ?? "").trim();
  if (!viewerEmail.includes("@")) {
    return NextResponse.json({ error: "invalid email" }, { status: 400 });
  }
  try {
    const res = await serverFetch("/shares", {
      method: "POST",
      body: {
        viewerEmail,
        canViewAnalysis: !!body.canViewAnalysis,
        canUploadDocs: !!body.canUploadDocs,
      },
    });
    return NextResponse.json(res ?? { ok: true }, { status: 201 });
  } catch (e) {
    return mapError(e);
  }
}

/** Revoke a share by id (?id=). */
export async function DELETE(request: Request) {
  const id = new URL(request.url).searchParams.get("id");
  if (!id) return NextResponse.json({ error: "missing id" }, { status: 400 });
  try {
    await serverFetch(`/shares/${encodeURIComponent(id)}`, { method: "DELETE" });
    return new NextResponse(null, { status: 204 });
  } catch (e) {
    return mapError(e);
  }
}
