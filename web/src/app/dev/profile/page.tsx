"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { ProfileView } from "@/components/profile/ProfileView";
import { profileFixture, profileProFixture } from "@/lib/fixtures/profile";

// Dev preview via ?v=:
//   (default)  free user — Upgrade card + "Free · Upgrade" plan row
//   pro        Pro user — "Pro · Annual" plan row + Manage subscription
// Dev routes 404 in production (handled globally). Variant read CLIENT-side so
// the route exports statically for the native capture build (§F.2).
export default function DevProfilePage() {
  return (
    <Suspense fallback={null}>
      <DevProfileInner />
    </Suspense>
  );
}

function DevProfileInner() {
  const v = useSearchParams().get("v") ?? undefined;
  const profile = v === "pro" ? profileProFixture : profileFixture;
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState={profile.subState}>
        <ProfileView profile={profile} />
      </AppShell>
    </ThemeProvider>
  );
}
