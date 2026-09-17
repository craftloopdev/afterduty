import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import type { SubscriptionResult } from "@/lib/api/endpoints";

// P1-11: ask/page.tsx must use the TRI-STATE subscription read. A billing
// outage ("error") renders a neutral unavailable panel — NEVER the Pro upsell
// (the viewer may be a paying subscriber mid-outage). Only a confirmed "pro"
// unlocks the composer and the ?topic prefill.

const { loadMessagesMock, getSubscriptionResultMock } = vi.hoisted(() => ({
  loadMessagesMock: vi.fn(),
  getSubscriptionResultMock: vi.fn(),
}));
vi.mock("@/lib/api/endpoints", () => ({
  loadMessages: loadMessagesMock,
  getSubscriptionResult: getSubscriptionResultMock,
}));
// The native wrapper pulls in the Capacitor transport chain — not under test.
vi.mock("@/components/native/NativeAsk", () => ({ NativeAsk: () => null }));
vi.mock("next/link", () => ({
  default: ({
    href,
    children,
    ...rest
  }: React.PropsWithChildren<{ href: string } & Record<string, unknown>>) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import AskPage from "./page";

function sub(state: SubscriptionResult["state"]): SubscriptionResult {
  return state === "pro"
    ? { state, status: { active: true } }
    : state === "free"
      ? { state, status: null }
      : { state: "error", status: null };
}

async function renderAsk(topic?: string) {
  const ui = await AskPage({ searchParams: Promise.resolve({ topic }) });
  render(ui);
}

beforeEach(() => {
  loadMessagesMock.mockReset().mockResolvedValue([]);
  getSubscriptionResultMock.mockReset();
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
});

describe("AskPage subscription tri-state (P1-11)", () => {
  it("renders the neutral unavailable state on a subscription-check ERROR — never the upsell", async () => {
    getSubscriptionResultMock.mockResolvedValue(sub("error"));
    await renderAsk("PTSD");

    expect(screen.getByText("Chat is unavailable right now.")).toBeInTheDocument();
    expect(screen.queryByText("Upgrade to Pro")).toBeNull();
    expect(screen.queryByText(/Pro feature/)).toBeNull();
    expect(screen.queryByLabelText("Ask a question about your claim")).toBeNull();
  });

  it("unlocks the composer (and the topic prefill) for a confirmed pro user", async () => {
    getSubscriptionResultMock.mockResolvedValue(sub("pro"));
    await renderAsk("PTSD");

    const ta = screen.getByLabelText("Ask a question about your claim");
    expect(ta).toHaveValue("PTSD");
    expect(screen.queryByText("Chat is unavailable right now.")).toBeNull();
    expect(screen.queryByText("Upgrade to Pro")).toBeNull();
  });

  it("upsells only a CONFIRMED free user", async () => {
    getSubscriptionResultMock.mockResolvedValue(sub("free"));
    await renderAsk();

    expect(screen.getByText("Upgrade to Pro")).toBeInTheDocument();
    expect(screen.queryByText("Chat is unavailable right now.")).toBeNull();
  });
});
