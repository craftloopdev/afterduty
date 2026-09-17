import { NATIVE } from "@/lib/platform";
import { NativeAppLayout } from "@/components/native/NativeAppLayout";
import { redirect } from "next/navigation";
import { cookies } from "next/headers";
import { getMe, getSubscriptionResult, probeAnalysisBlocked } from "@/lib/api/endpoints";
import { VIEW_AS_COOKIE } from "@/lib/api/transport";
import { displayName } from "@/lib/format";
import { UnauthorizedError } from "@/lib/api/errors";
import { SessionWatcher } from "@/components/auth/SessionWatcher";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import type { SharedClaimRef, ViewerVM } from "@/lib/models/vm";

// Server-side auth gate + app chrome for every authenticated route. On WEB this
// reads the session cookie (via the BFF) and bounces unauthenticated users to
// /login before any authed content renders. On NATIVE (static export) there is
// no cookie and no RSC — the client `<NativeAppLayout>` gate replaces it (§A.4),
// holding the splash until auth resolves. `NATIVE` is statically true in the
// export build, so the RSC reads below (and their cookie/server-only calls) are
// never reached; the dead branch is eliminated by the bundler.
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  if (NATIVE) return <NativeAppLayout>{children}</NativeAppLayout>;

  let me;
  try {
    me = await getMe();
  } catch (e) {
    if (e instanceof UnauthorizedError) redirect("/login");
    throw e;
  }
  // Tri-state so an outage never flashes the upsell to a Pro user (and never
  // shows a Pro affordance to a free user when we can't be sure).
  const sub = await getSubscriptionResult();

  // Viewer mode (P0-8). `me.sharedProfiles` lists the claims OTHERS shared with
  // this user (accepted + not revoked; own claim excluded by the backend); the
  // cp_view_as cookie is the current selection, forwarded by serverFetch as
  // X-View-As on every GET in this render.
  const sharedClaims: SharedClaimRef[] = (me.sharedProfiles ?? [])
    .filter((p) => !p.isOwn && typeof p.claimId === "number")
    .map((p) => ({
      claimId: p.claimId,
      ownerName: p.ownerName?.trim() || p.ownerEmail || "Shared claim",
    }));

  let viewer: ViewerVM | undefined;
  const rawViewAs = (await cookies()).get(VIEW_AS_COOKIE)?.value;
  if (rawViewAs) {
    const selected = sharedClaims.find((c) => String(c.claimId) === rawViewAs);
    if (!selected) {
      // Stale selection — the share was revoked, or a different account signed
      // in on this browser. Clear it server-side and land on the own claim so
      // no read ever runs against a claim this user can't access.
      redirect("/api/view-as/exit");
    }
    const share = me.sharedProfiles?.find((p) => p.claimId === selected.claimId);
    const canViewAnalysis = share?.canViewAnalysis === true;
    // Owner-Pro dependency: with analysis granted, a 403 on the conditions
    // probe means the claim OWNER's Pro lapsed (ClaimAccessService VIEW_ANALYSIS).
    // React `cache` dedupes this with the page's own conditions read. An
    // upstream outage is an honest unknown — never blamed on the owner's plan.
    let analysisBlocked = !canViewAnalysis;
    if (canViewAnalysis) {
      try {
        analysisBlocked = await probeAnalysisBlocked();
      } catch (e) {
        if (e instanceof UnauthorizedError) redirect("/login");
        analysisBlocked = false;
      }
    }
    viewer = {
      viewing: true,
      claimId: selected.claimId,
      ownerName: selected.ownerName,
      canViewAnalysis,
      canUploadDocs: share?.canUploadDocs === true,
      analysisBlocked,
    };
  }

  return (
    <ThemeProvider>
      <SessionWatcher />
      <AppShell
        userName={displayName(me)}
        branch={null}
        subState={sub.state}
        viewer={viewer}
        sharedClaims={sharedClaims}
      >
        {children}
      </AppShell>
    </ThemeProvider>
  );
}
