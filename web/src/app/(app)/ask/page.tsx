import { loadMessages, getSubscriptionResult } from "@/lib/api/endpoints";
import { ChatView } from "@/components/chat/ChatView";
import { NATIVE } from "@/lib/platform";
import { NativeAsk } from "@/components/native/NativeAsk";

// Tri-state subscription read (P1-11): a billing outage must NOT collapse to
// "free" — that would show a paying user the Pro upsell. `error` renders a
// neutral "chat unavailable" panel instead; only a confirmed "pro" unlocks the
// composer (and the ?topic prefill).
export default async function AskPage({
  searchParams,
}: {
  searchParams: Promise<{ topic?: string }>;
}) {
  if (NATIVE) return <NativeAsk />;

  const [initial, sub, sp] = await Promise.all([
    loadMessages(),
    getSubscriptionResult(),
    searchParams,
  ]);
  const pro = sub.state === "pro";
  const prefill = pro ? sp.topic : undefined;
  return (
    <ChatView initial={initial} pro={pro} unavailable={sub.state === "error"} prefill={prefill} />
  );
}
