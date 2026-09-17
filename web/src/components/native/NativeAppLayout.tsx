"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  getMe,
  getSubscriptionResult,
  probeAnalysisBlocked,
  useLoader,
} from "@/lib/api/endpoints.native";
import { configureDirectApi, setViewAs } from "@/lib/api/direct";
import { authDriver, type AuthStatus } from "@/lib/auth";
import { displayName } from "@/lib/format";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import type { SharedClaimRef, SubscriptionState, ViewerVM } from "@/lib/models/vm";
import { hideSplash } from "@/lib/native/splash";
import { installDeepLinks } from "@/lib/native/deep-links.client";
import { isPublicDest } from "@/lib/native/deep-links";
import { configureRevenueCat, rcLogIn, rcLogOut } from "@/lib/native/revenuecat";
import { useOnline } from "@/lib/native/network";
import { OfflineState } from "@/components/ui/OfflineState";
import { ErrorState } from "@/components/ui/ErrorState";

// Native auth gate (capacitor-ios-spec §A.4) — replaces `proxy.ts` + the RSC
// `(app)/layout.tsx` gate. It subscribes to the AuthDriver's auth status:
//   - `undetermined` → keep the held Capacitor splash up (no flash, §D.2/§H.5).
//   - `signed-out`   → redirect to /login (proxy.ts's first rule).
//   - `signed-in`    → load getMe + getSubscriptionResult and render the SAME
//                      ThemeProvider + AppShell the server layout renders today
//                      (SessionWatcher is web-only — its cookie rotation has no
//                      native job).
// The DirectApiClient's token source is wired here at boot from the driver, with
// a sign-out-on-auth-loss handler for the 401-retry-then-signout path (§B.3).

// Wire the native transport's token provider once, before any data load.
configureDirectApi(
  (opts) => authDriver.getToken(opts),
  () => authDriver.signOut(),
);

// Configure RevenueCat once at module load (native only; no-op on web — §C.1).
// logIn/logOut follow the auth status inside the gate so the RC subscriber always
// tracks the Firebase UID the backend keys on.
void configureRevenueCat();

export function NativeAppLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [status, setStatus] = useState<AuthStatus>("undetermined");
  // Last inbound PUBLIC deep-link destination (share-accept) seen before auth
  // resolved. On a cold launch from such a universal link the
  // gate's signed-out `/login` replace would otherwise race and clobber it
  // (dim 6). We park it here and let the signed-out branch honor it instead of
  // bouncing to /login. A ref (not state) so capturing it never re-renders the
  // gate mid-redirect.
  const pendingPublicDest = useRef<string | null>(null);
  // Biometric device-login is attempted AT MOST ONCE per launch, before we ever
  // bounce to /login. A ref (not state) so the attempt-in-flight guard never
  // re-renders the gate; it also latches "already tried" so a later signed-out
  // (e.g. the user signs out in-app) goes straight to /login without re-prompting
  // Face ID.
  const deviceLoginTried = useRef(false);

  useEffect(() => {
    const unsub = authDriver.watchAuth(setStatus);
    return unsub;
  }, []);

  // Universal-link / custom-scheme deep links → in-app routes (§D.3). Installed
  // once at the gate so share invites, email-link reopen, and the screenshot
  // pipeline resolve regardless of which screen is mounted. Public pre-auth
  // destinations are also recorded so the signed-out branch can replay them
  // rather than redirect to /login.
  useEffect(
    () =>
      installDeepLinks((dest) => {
        if (isPublicDest(dest)) pendingPublicDest.current = dest;
        router.replace(dest);
      }),
    [router],
  );

  useEffect(() => {
    if (status === "signed-out") {
      // Cross-account hygiene: never carry a viewer-mode selection into the
      // next sign-in (P0-8).
      setViewAs(null);
      void rcLogOut();
      // A cold deep link into a pre-auth public flow (share preview / email-link
      // completion) must survive the gate — honor it instead of /login so the
      // inbound token/oobCode isn't dropped (dim 6, §B.5/§A.3a). No biometric
      // prompt over a public deep-link.
      const pending = pendingPublicDest.current;
      if (pending) {
        pendingPublicDest.current = null;
        hideSplash();
        router.replace(pending);
        return;
      }

      // Biometric device-login (§B2): on the FIRST signed-out resolution of a
      // launch, if this device has an enrolled credential, force a fresh Face ID /
      // Touch ID prompt and exchange the device secret for a session — no OTP. The
      // held splash stays up while we prompt (no /login flash behind the sheet).
      // `deviceLogin` NEVER throws and resolves false for no-credential / cancel /
      // biometryChange / revoked, so we then fall through to /login exactly as
      // before (never a lockout). A success fires authStateChange → the gate flips
      // to signed-in and this effect re-runs down the signed-in branch.
      if (!deviceLoginTried.current) {
        deviceLoginTried.current = true;
        // deviceLogin is an OPTIONAL pre-session convenience (skip OTP for a
        // returning device). It must NEVER be able to hold the launch splash: a
        // hung pre-session plugin call (e.g. the lazy biometric import stalling in
        // the WebView) would otherwise leave the app on the held splash forever —
        // "app never finishes loading" (App Store 2.1(a)). Bound it: if it hasn't
        // resolved in time, treat it as "no biometric" and fall through to /login.
        const deviceLoginBounded = Promise.race([
          authDriver.deviceLogin(),
          new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 2500)),
        ]);
        void deviceLoginBounded.then((ok) => {
          if (ok) return; // authStateChange will re-drive the gate to signed-in
          hideSplash();
          router.replace("/login");
        });
        return;
      }

      hideSplash();
      router.replace("/login");
    } else if (status === "signed-in") {
      // Associate the RC subscriber with the Firebase UID after sign-in resolves
      // (the JS SDK uid is synced by the native driver — §C.1). configureRevenueCat
      // has already run at module load; rcLogIn no-ops until it has.
      void configureRevenueCat().then(() => {
        const uid = authDriver.getUid();
        if (uid) void rcLogIn(uid);
      });
    }
  }, [status, router]);

  if (status !== "signed-in") {
    // Splash (held by launchAutoHide:false) covers undetermined; signed-out is
    // mid-redirect. Render nothing so there is no white flash (§H.5).
    return null;
  }

  return <SignedInShell>{children}</SignedInShell>;
}

function SignedInShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const me = useLoader(getMe);
  const sub = useLoader(getSubscriptionResult);
  const online = useOnline();
  // Viewer-mode selection (P0-8) — the native mirror of the web's cp_view_as
  // cookie. React state drives the UI; the module-level `setViewAs` in
  // direct.ts drives the X-View-As header on every subsequent GET.
  const [viewSel, setViewSel] = useState<SharedClaimRef | null>(null);

  const onViewAsChange = useCallback(
    (claim: SharedClaimRef | null) => {
      // Order matters: the transport must carry the new selection BEFORE the
      // keyed shell remounts and every page loader refetches.
      setViewAs(claim?.claimId ?? null);
      setViewSel(claim);
      router.replace("/");
    },
    [router],
  );

  useEffect(() => {
    // Once the first authed read resolves (data or error), drop the splash.
    if (!me.loading && !sub.loading) hideSplash();
  }, [me.loading, sub.loading]);

  // If the selected share vanished (revoked; or /me re-resolved without it),
  // drop the selection BEFORE anything renders against a dead X-View-As.
  // Sanctioned derived-state reset (setState during render, guarded), plus the
  // idempotent transport-value clear it must stay in lockstep with.
  if (viewSel && me.data && !me.data.sharedProfiles?.some((p) => p.claimId === viewSel.claimId)) {
    setViewAs(null);
    setViewSel(null);
  }

  // Keep the splash until the first reads settle (no half-painted shell).
  if (me.data == null && me.loading) return null;
  if (me.error) {
    // A cold launch with no connectivity fails getMe here (the auth gate already
    // resolved `signed-in` from the keychain). Show the BRANDED offline shell with
    // Retry instead of hanging on a hidden splash → white screen (§D.3/§H.5).
    // A non-offline post-auth getMe failure is rare (the DirectApiClient retried +
    // signed out on a hard 401), so a generic retry covers the transient case.
    if (!online) return <OfflineState onRetry={me.refetch} />;
    return <ErrorState onRetry={me.refetch} body="Check your connection and try again." />;
  }

  const subState = sub.data?.state ?? "error";

  const sharedClaims: SharedClaimRef[] = (me.data?.sharedProfiles ?? [])
    .filter((p) => !p.isOwn && typeof p.claimId === "number")
    .map((p) => ({
      claimId: p.claimId,
      ownerName: p.ownerName?.trim() || p.ownerEmail || "Shared claim",
    }));

  const share = viewSel
    ? me.data?.sharedProfiles?.find((p) => p.claimId === viewSel.claimId)
    : undefined;
  const viewer: ViewerVM | undefined =
    viewSel && share
      ? {
          viewing: true,
          claimId: viewSel.claimId,
          ownerName: viewSel.ownerName,
          canViewAnalysis: share.canViewAnalysis === true,
          canUploadDocs: share.canUploadDocs === true,
          // Docs-only shares are blocked by definition; analysis-granted shares
          // get the owner-Pro probe inside the keyed shell below.
          analysisBlocked: share.canViewAnalysis !== true,
        }
      : undefined;

  return (
    <ThemeProvider>
      {/* Keyed by selection: switching claims remounts the shell AND every page
          loader below it — the native equivalent of the web's full navigation. */}
      <NativeShell
        key={viewSel?.claimId ?? "own"}
        userName={me.data ? displayName(me.data) : ""}
        subState={subState}
        viewer={viewer}
        sharedClaims={sharedClaims}
        onViewAsChange={onViewAsChange}
      >
        {children}
      </NativeShell>
    </ThemeProvider>
  );
}

const noProbe = async () => false;

/**
 * The keyed shell mount. Runs the owner-Pro dependency probe (P0-8) at mount
 * when the selected share grants analysis: a 403 on the conditions read means
 * the claim OWNER's Pro lapsed, and the banner must say so honestly. The
 * loader identity is fixed at mount — the parent remounts us (key) whenever
 * the selection changes.
 */
function NativeShell({
  userName,
  subState,
  viewer,
  sharedClaims,
  onViewAsChange,
  children,
}: {
  userName: string;
  subState: SubscriptionState;
  viewer: ViewerVM | undefined;
  sharedClaims: SharedClaimRef[];
  onViewAsChange: (claim: SharedClaimRef | null) => void;
  children: React.ReactNode;
}) {
  const needProbe = viewer?.viewing === true && viewer.canViewAnalysis;
  const probe = useLoader(needProbe ? probeAnalysisBlocked : noProbe);
  const resolved: ViewerVM | undefined = viewer
    ? { ...viewer, analysisBlocked: viewer.analysisBlocked || probe.data === true }
    : undefined;

  return (
    <AppShell
      userName={userName}
      branch={null}
      subState={subState}
      viewer={resolved}
      sharedClaims={sharedClaims}
      onViewAsChange={onViewAsChange}
    >
      {children}
    </AppShell>
  );
}
