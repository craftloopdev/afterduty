import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { SESSION_COOKIE } from "@/lib/constants";
import { UnauthorizedError } from "@/lib/api/errors";

// Permanently delete the signed-in user's account (cascade handled by Spring),
// then clear the local session cookie. Triggered only by the user from the UI.
export async function DELETE() {
  try {
    await serverFetch("/auth/account", { method: "DELETE" });
  } catch (e) {
    if (e instanceof UnauthorizedError) {
      return NextResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    return NextResponse.json({ error: "upstream" }, { status: 500 });
  }
  (await cookies()).delete(SESSION_COOKIE);
  return new NextResponse(null, { status: 204 });
}
