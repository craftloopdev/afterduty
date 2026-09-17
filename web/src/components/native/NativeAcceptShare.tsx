"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { AcceptShare } from "@/components/share/AcceptShare";
import { EmptyState } from "@/components/ui/EmptyState";

// Native accept-share wrapper for the query-param twin route (§A.3a). Reading
// `searchParams` in an RSC makes a page dynamic (illegal under `output:"export"`),
// so the token is read CLIENT-SIDE via `useSearchParams` here. The deep-link
// handler maps the universal-link `/accept-share/{token}` to `?token=` (§D.3).
// `AcceptShare` itself already routes its preview/accept calls through the
// mutations facade (direct `/shares/accept` on native).
//
// `useSearchParams` requires a Suspense boundary during static prerender (the CSR
// bailout) — without it the export build fails. The boundary renders nothing
// (the held splash / instant client resolution covers it).
function AcceptShareInner() {
  const token = useSearchParams().get("token");
  if (!token) {
    return (
      <EmptyState
        icon="share"
        title="Invite link incomplete"
        body="This invite link is missing its code. Ask the veteran to send a fresh invite."
      />
    );
  }
  return <AcceptShare token={token} />;
}

export function NativeAcceptShare() {
  return (
    <Suspense fallback={null}>
      <AcceptShareInner />
    </Suspense>
  );
}
