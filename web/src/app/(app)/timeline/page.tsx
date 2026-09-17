import { loadTimeline } from "@/lib/api/endpoints";
import { TimelineView } from "@/components/timeline/TimelineView";
import { NATIVE } from "@/lib/platform";
import { NativeTimeline } from "@/components/native/NativeTimeline";

// The claim timeline — a chronological journal of every analysis update
// (roadmap §5 item 6). Reached from Home's digest card ("See all updates"),
// the discreet Home link, and Profile; deliberately NOT a bottom-nav tab.
export default async function TimelinePage() {
  // Native (static export): client wrapper fetches via DirectApiClient (§A.3).
  if (NATIVE) return <NativeTimeline />;

  const vm = await loadTimeline();
  return <TimelineView days={vm.days} />;
}
