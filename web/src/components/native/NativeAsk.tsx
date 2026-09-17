"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { loadMessages, getSubscriptionResult, useLoader } from "@/lib/api/endpoints.native";
import type { SubscriptionResult } from "@/lib/api/endpoints.native";
import { ChatView } from "@/components/chat/ChatView";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Ask wrapper (§A.3). Web reads messages + subscription in an RSC
// `Promise.all` and `searchParams.topic`; natively one combined loader fetches
// both and `useSearchParams()` supplies the prefill (wrapped in Suspense for the
// static-prerender CSR bailout). The composer is gated by the TRI-STATE
// subscription read exactly as on web (P1-11): "pro" unlocks the composer (and
// the topic prefill), "free" shows the upsell, and "error" — an outage, NOT
// free — shows the neutral unavailable panel, never the upsell.
async function loadAsk(): Promise<{
  initial: Awaited<ReturnType<typeof loadMessages>>;
  sub: SubscriptionResult["state"];
}> {
  const [initial, sub] = await Promise.all([loadMessages(), getSubscriptionResult()]);
  return { initial, sub: sub.state };
}

export function NativeAsk() {
  return (
    <Suspense fallback={<HomeLoading />}>
      <AskInner />
    </Suspense>
  );
}

function AskInner() {
  const state = useLoader(loadAsk);
  const topic = useSearchParams().get("topic") ?? undefined;
  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {({ initial, sub }) => (
        <ChatView
          initial={initial}
          pro={sub === "pro"}
          unavailable={sub === "error"}
          prefill={sub === "pro" ? topic : undefined}
        />
      )}
    </LoaderBoundary>
  );
}
