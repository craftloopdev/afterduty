import type { NextConfig } from "next";
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";

// Trace only this app's files into the standalone bundle — we live inside a
// monorepo that also holds flutter_frontend/ and spring-backend/.
const appRoot = dirname(fileURLToPath(import.meta.url));

// The Firebase project that serves the auth handler (proxied so OAuth runs
// same-origin → no Safari ITP cross-origin block on Google/Apple sign-in).
const FIREBASE_AUTH_HOST = "craftloop-va-claim.firebaseapp.com";

// Two build targets from one codebase (capacitor-ios-spec §A.1, decision D1):
//   - web (default):      output:"standalone" → .next/standalone, the Cloud Run BFF.
//   - native (NATIVE=1):  output:"export"     → web/out/, Capacitor's webDir.
// The native bundle is a fully static export: RSC route handlers, cookies(),
// and rewrites cannot run inside it, so those responsibilities move to the
// DirectApiClient + client auth gate (§A). The branch below keeps the web path
// byte-for-byte unchanged when NATIVE is unset.
const isNative = process.env.NEXT_PUBLIC_NATIVE === "1";

// Security response headers (VCP-HARD-05). These used to live in the Flutter
// frontend's nginx config; that surface was deleted with the Flutter app and the
// Next BFF that replaced it shipped with none, so the product handling medical
// records was serving fewer protections than the static marketing site.
//
// Only the web (standalone) target gets these: the native build is a static
// export loaded from the Capacitor WebView, where there is no server to set them.
//
// Enforcing as of 2026-08-12, after shipping it Report-Only and exercising the
// real deployed app in a browser with the console open: login page, email OTP
// request + verify + session establishment, home, conditions, documents, Ask AI,
// and — the path most likely to break — phone OTP, which falls back to reCAPTCHA
// v2 and renders its iframe. Zero violations on any of them. Stripe checkout is
// reached via window.location.assign (StripePaywall.tsx:77), a navigation rather
// than a cross-origin form POST, so form-action 'self' does not block it.
//
// Caveat worth knowing: 'unsafe-inline' in script-src is required by Next's
// inline hydration bootstrap and substantially weakens the XSS protection a CSP
// would otherwise give. The directives carrying real weight here are therefore
// object-src 'none', base-uri 'self', form-action 'self' and frame-ancestors
// 'none'. Nonce-based scripts would be the next hardening step.
const CSP = [
  "default-src 'self'",
  // 'unsafe-inline' covers Next's hydration/RSC bootstrap; gstatic+google serve reCAPTCHA.
  "script-src 'self' 'unsafe-inline' https://www.gstatic.com https://www.google.com",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://www.gstatic.com",
  "font-src 'self' data:",
  // The browser talks to our own origin (the BFF proxies to the API server-side);
  // the Firebase JS SDK calls Identity Toolkit and token refresh directly.
  "connect-src 'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.googleapis.com",
  // reCAPTCHA challenge iframe for phone-number sign-in.
  "frame-src 'self' https://www.google.com https://recaptcha.google.com",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
  "upgrade-insecure-requests",
].join("; ");

const SECURITY_HEADERS = [
  // No `preload`: enrolling in the browser preload list is effectively
  // irreversible, and afterduty.app may still move to afterduty.com.
  { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // Nothing in the web app uses these; the native camera goes through the
  // Capacitor plugin, not getUserMedia, so denying camera here is safe.
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=(), usb=()" },
  { key: "Content-Security-Policy", value: CSP },
];

const nextConfig: NextConfig = isNative
  ? {
      // Static export → web/out/. No image optimizer exists in export mode;
      // there is no next/image usage in src/, so this is belt-and-braces (§A.6).
      output: "export",
      outputFileTracingRoot: appRoot,
      images: { unoptimized: true },
      // No rewrites in export mode — native OAuth is the plugin, never a web
      // popup, so the /__/auth same-origin proxy isn't needed (§A.1).
    }
  : {
      // Produces .next/standalone (run with `node server.js`) for the Cloud Run BFF.
      output: "standalone",
      outputFileTracingRoot: appRoot,
      // Self-host the Firebase auth handler: the browser hits same-origin
      // /__/auth/* and /__/firebase/*, which we proxy to the Firebase domain.
      async rewrites() {
        return [
          { source: "/__/auth/:path*", destination: `https://${FIREBASE_AUTH_HOST}/__/auth/:path*` },
          { source: "/__/firebase/:path*", destination: `https://${FIREBASE_AUTH_HOST}/__/firebase/:path*` },
        ];
      },
      async headers() {
        return [{ source: "/:path*", headers: SECURITY_HEADERS }];
      },
    };

export default nextConfig;
