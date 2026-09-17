import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/constants";

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

// BFF mutation lane: forward a multipart evidence upload to Spring with the
// Bearer token from the httpOnly cookie. Status codes (402/409/413) pass through.
export async function POST(request: Request) {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return NextResponse.json({ error: "unauthorized" }, { status: 401 });

  const inForm = await request.formData();
  const file = inForm.get("file");
  if (!(file instanceof File)) {
    return NextResponse.json({ error: "no file" }, { status: 400 });
  }

  const out = new FormData();
  out.append("file", file, file.name);

  // Do NOT set Content-Type — fetch sets the multipart boundary itself.
  const res = await fetch(`${API_BASE}/claim/evidence`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: out,
  });

  const body = await res.text();
  return new NextResponse(body || null, {
    status: res.status,
    headers: { "content-type": res.headers.get("content-type") ?? "application/json" },
  });
}
