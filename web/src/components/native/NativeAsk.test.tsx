import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

// P1-11 (native half): NativeAsk must gate the composer on the TRI-STATE
// subscription read. An outage ("error") renders the neutral unavailable
// panel, never the Pro upsell; only a confirmed "pro" unlocks the composer.

const { loadMessagesMock, getSubscriptionResultMock } = vi.hoisted(() => ({
  loadMessagesMock: vi.fn(),
  getSubscriptionResultMock: vi.fn(),
}));
vi.mock("@/lib/api/endpoints.native", async () => {
  // Real useLoader (the loader lifecycle IS part of what renders), stub reads.
  const { useLoader } = await vi.importActual<typeof import("@/lib/hooks/useLoader")>(
    "@/lib/hooks/useLoader",
  );
  return {
    loadMessages: loadMessagesMock,
    getSubscriptionResult: getSubscriptionResultMock,
    useLoader,
  };
});
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams("topic=PTSD"),
}));
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

import { NativeAsk } from "./NativeAsk";

beforeEach(() => {
  loadMessagesMock.mockReset().mockResolvedValue([]);
  getSubscriptionResultMock.mockReset();
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
});

describe("NativeAsk subscription tri-state (P1-11)", () => {
  it("renders the neutral unavailable state on a subscription-check ERROR — never the upsell", async () => {
    getSubscriptionResultMock.mockResolvedValue({ state: "error", status: null });
    render(<NativeAsk />);

    expect(await screen.findByText("Chat is unavailable right now.")).toBeInTheDocument();
    expect(screen.queryByText("Upgrade to Pro")).toBeNull();
    expect(screen.queryByLabelText("Ask a question about your claim")).toBeNull();
  });

  it("unlocks the composer + topic prefill for a confirmed pro user", async () => {
    getSubscriptionResultMock.mockResolvedValue({ state: "pro", status: { active: true } });
    render(<NativeAsk />);

    const ta = await screen.findByLabelText("Ask a question about your claim");
    expect(ta).toHaveValue("PTSD");
    expect(screen.queryByText("Chat is unavailable right now.")).toBeNull();
  });

  it("upsells only a CONFIRMED free user", async () => {
    getSubscriptionResultMock.mockResolvedValue({ state: "free", status: null });
    render(<NativeAsk />);

    expect(await screen.findByText("Upgrade to Pro")).toBeInTheDocument();
    expect(screen.queryByText("Chat is unavailable right now.")).toBeNull();
  });
});
