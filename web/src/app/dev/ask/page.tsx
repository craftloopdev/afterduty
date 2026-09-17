"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { ChatView } from "@/components/chat/ChatView";
import {
  messagesFixture,
  messagesMarkdownFixture,
  messagesFailedFixture,
} from "@/lib/fixtures/messages";

// Dev preview for the rebuilt ChatView. Variants via ?v=:
//   (default)  — Pro user, basic thread + live composer
//   markdown   — Pro user, markdown formatting + cite: citation chips
//   failed     — Pro user, a failed-send bubble (retry/dismiss affordances)
//   free       — free user, locked composer + Pro suggestion chips
// Dev routes 404 in production (handled globally). The variant is read CLIENT-
// side (useSearchParams under Suspense) so this fixture route exports statically
// for the native CAPTURE build (§F.2/§F.3 — the screenshot pipeline deep-links
// /dev/ask); on web the behavior is unchanged.
export default function DevAskPage() {
  return (
    <Suspense fallback={null}>
      <DevAskInner />
    </Suspense>
  );
}

function DevAskInner() {
  const v = useSearchParams().get("v") ?? undefined;

  let initial = messagesFixture;
  let pro = true;
  if (v === "markdown") initial = messagesMarkdownFixture;
  else if (v === "failed") initial = messagesFailedFixture;
  else if (v === "free") {
    // Empty thread so the locked panel + Pro suggestion chips both show.
    initial = [];
    pro = false;
  }

  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState={pro ? "pro" : "free"}>
        <ChatView initial={initial} pro={pro} prefill={v === "prefill" ? "PTSD" : undefined} />
      </AppShell>
    </ThemeProvider>
  );
}
