"use client";

import { useState } from "react";
import { usePathname } from "next/navigation";
import { Sidebar } from "./Sidebar";
import { MobileNav } from "./MobileNav";
import { TopBar } from "./TopBar";
import { DisclaimerModal } from "./DisclaimerModal";
import { NOT_VIEWING, ViewerProvider } from "@/components/viewer/ViewerContext";
import { ViewerBanner } from "@/components/viewer/ViewerBanner";
import { ViewerSwitcher } from "@/components/viewer/ViewerSwitcher";
import type { SharedClaimRef, SubscriptionState, ViewerVM } from "@/lib/models/vm";
import styles from "./AppShell.module.css";

interface AppShellProps {
  userName: string;
  branch?: string | null;
  /** Tri-state billing truth. "error" (unknown) shows no upsell and no Pro badge. */
  subState: SubscriptionState;
  /** Viewer mode (P0-8): set when a SHARED claim is selected — read-only UI. */
  viewer?: ViewerVM;
  /** Accepted shares → "View <owner>'s claim" switcher entries (P0-8). */
  sharedClaims?: SharedClaimRef[];
  /**
   * Native override for switch/exit (null = back to my claim): the layout
   * swaps the DirectApiClient selection and refetches. Web (RSC layout) can't
   * pass functions — the viewer components then use the BFF route + full
   * navigation themselves.
   */
  onViewAsChange?: (claim: SharedClaimRef | null) => void;
  children: React.ReactNode;
}

export function activeKey(rawPathname: string): string {
  // The fixture-fed /dev/* pages (screenshot pipeline, capture builds only)
  // mirror the real routes one level down; strip the prefix so they get the
  // same title and active tab. No production route begins with /dev.
  const pathname = rawPathname.replace(/^\/dev(?=\/|$)/, "") || "/";
  if (pathname === "/" || pathname.startsWith("/home")) return "/";
  // "/timeline" and "/learn" get a TopBar title but deliberately match NO nav
  // tab — they're reached from Home/Profile, not extra tabs.
  for (const k of ["/conditions", "/steps", "/documents", "/profile", "/share", "/upgrade", "/ask", "/timeline", "/learn"]) {
    if (pathname.startsWith(k)) return k;
  }
  return "/";
}

function titleFor(active: string, firstName: string): { title: string; sub?: string } {
  switch (active) {
    case "/conditions":
      return { title: "Conditions", sub: "Built from your evidence · each scored on the VA triad" };
    case "/steps":
      return { title: "Next Steps", sub: "Close the gaps that strengthen the evidence behind your estimate." };
    case "/documents":
      return { title: "Documents", sub: "Everything we've processed to build your claim." };
    case "/profile":
      return { title: "Profile" };
    case "/share":
      return { title: "Share with a VSO", sub: "Invite an accredited rep to securely review your claim." };
    case "/upgrade":
      return { title: "Upgrade to Pro", sub: "AI extraction, condition synthesis & gap analysis." };
    case "/ask":
      return { title: "Ask AI", sub: "AI-assisted answers about your claim — not legal advice." };
    case "/timeline":
      return { title: "Claim timeline", sub: "Every update to your analysis, in order." };
    case "/learn":
      // Sole carrier of the free-tier promise since the hub's own header was
      // removed as a duplicate of this TopBar (the hub is $0/no-subscription).
      return {
        title: "Learn",
        sub: "Plain-language guides to the VA claims process — free for every veteran, no subscription needed.",
      };
    default:
      return {
        title: firstName ? `Welcome back, ${firstName}` : "Welcome back",
        sub: "Here's where your claim stands today.",
      };
  }
}

export function AppShell({
  userName,
  branch,
  subState,
  viewer,
  sharedClaims,
  onViewAsChange,
  children,
}: AppShellProps) {
  const pathname = usePathname();
  const active = activeKey(pathname);
  const firstName = userName.trim().split(/\s+/)[0] ?? "";
  const initial = (userName.trim()[0] ?? "?").toUpperCase();
  const { title, sub } = titleFor(active, firstName);
  // The only overlay left is the disclaimer sheet — "Add evidence" dissolved
  // into the Documents page (P2-1).
  const [discOpen, setDiscOpen] = useState(false);
  const viewerState = viewer ?? NOT_VIEWING;
  const viewing = viewerState.viewing;

  return (
    <ViewerProvider value={viewerState}>
      <div className={styles.app} data-viewer={viewing ? "1" : undefined}>
        <Sidebar
          active={active}
          userName={userName || "Your account"}
          branch={branch}
          subState={subState}
          viewing={viewing}
        />
        <main className={styles.main}>
          {/* Persistent viewer banner (P0-8) — above everything in the pane. */}
          {viewing && (
            <ViewerBanner
              viewer={viewerState}
              onExit={onViewAsChange ? () => onViewAsChange(null) : undefined}
            />
          )}
          <TopBar title={title} sub={sub} initial={initial} onDisclaimer={() => setDiscOpen(true)} />
          {/* Claim switcher: only on the user's OWN claim, when shares exist. */}
          {!viewing && !!sharedClaims?.length && (
            <ViewerSwitcher
              sharedClaims={sharedClaims}
              onSelect={onViewAsChange ? (c) => onViewAsChange(c) : undefined}
            />
          )}
          <div className={styles.content}>{children}</div>
        </main>
        <MobileNav active={active} />
        <DisclaimerModal open={discOpen} onClose={() => setDiscOpen(false)} />
      </div>
    </ViewerProvider>
  );
}
