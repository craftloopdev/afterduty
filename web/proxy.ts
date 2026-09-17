import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE, PUBLIC_PREFIXES } from "@/lib/constants";

// Next 16: Middleware is "Proxy". This is an OPTIMISTIC cookie-presence guard
// only — real JWT verification happens at Spring via the BFF. Keep it cheap.
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasSession = request.cookies.has(SESSION_COOKIE);
  const isPublic = PUBLIC_PREFIXES.some(
    (p) => pathname === p || pathname.startsWith(p + "/"),
  );

  if (!hasSession && !isPublic) {
    const url = new URL("/login", request.nextUrl);
    return NextResponse.redirect(url);
  }
  if (hasSession && (pathname === "/login" || pathname === "/finish-sign-in")) {
    return NextResponse.redirect(new URL("/", request.nextUrl));
  }
  return NextResponse.next();
}

export const config = {
  // Run on everything except API routes, Next internals, and the Firebase
  // auth handler (/__/auth, used for same-origin Apple sign-in).
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico|__/auth).*)"],
};
