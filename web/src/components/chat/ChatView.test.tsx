import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { ChatView } from "./ChatView";
import { CHAT_STALL_MS } from "./useChatStream";

// P1-10 / P1-11 rendering contract:
//  - the monthly AI cap renders a DISTINCT banner with the humanized reset date
//    and NO retry affordance, from BOTH wire shapes (429 status / body code);
//  - a subscription-check outage renders a neutral unavailable panel, never
//    the Pro upsell;
//  - a stalled stream shows the waiting-for-capacity copy, and a stalled drop
//    classifies as rate_limited (retryable), not the generic agent error.

const { streamChatMock, sendChatMock } = vi.hoisted(() => ({
  streamChatMock: vi.fn(),
  sendChatMock: vi.fn(),
}));
vi.mock("@/lib/api/mutations", () => ({
  streamChat: streamChatMock,
  sendChat: sendChatMock,
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

beforeEach(() => {
  streamChatMock.mockReset();
  sendChatMock.mockReset();
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  vi.useRealTimers();
});

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function sseResponse(frames: string[]): Response {
  const enc = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      for (const f of frames) c.enqueue(enc.encode(f));
      c.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "content-type": "text/event-stream" },
  });
}

function typeAndSend(text = "hello") {
  const ta = screen.getByLabelText("Ask a question about your claim");
  fireEvent.change(ta, { target: { value: text } });
  fireEvent.keyDown(ta, { key: "Enter" });
}

/** Drain pending microtasks (stream reads resolve on the microtask queue). */
const flush = () =>
  act(async () => {
    for (let i = 0; i < 20; i++) await Promise.resolve();
  });

describe("ChatView — usage cap (P1-10)", () => {
  it("renders the cap banner with a humanized date from the 429 wire shape, with no retry", async () => {
    streamChatMock.mockResolvedValue(
      jsonResponse(429, {
        error: "usage_limit_reached",
        code: "USAGE_LIMIT_REACHED",
        resumesAt: "2099-08-01T12:00:00Z",
      }),
    );
    render(<ChatView initial={[]} />);
    typeAndSend();

    expect(
      await screen.findByText("You've reached this month's AI limit — chat resumes August 1, 2099."),
    ).toBeInTheDocument();
    // Never raw ISO in the copy.
    expect(screen.queryByText(/2099-08-01/)).toBeNull();
    // NO retry affordance anywhere — retry cannot succeed until the reset.
    expect(screen.queryByText("Try again")).toBeNull();
    expect(screen.queryByText(/Retry/)).toBeNull();
  });

  it("renders the cap banner from today's 402+USAGE_LIMIT_REACHED wire shape (not the Pro gate)", async () => {
    streamChatMock.mockResolvedValue(
      jsonResponse(402, { code: "USAGE_LIMIT_REACHED", resumes_at: "2099-03-15T12:00:00Z" }),
    );
    render(<ChatView initial={[]} />);
    typeAndSend();

    expect(
      await screen.findByText("You've reached this month's AI limit — chat resumes March 15, 2099."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Try again")).toBeNull();
  });

  it("degrades to 'next month' when the cap arrives without resumesAt", async () => {
    streamChatMock.mockResolvedValue(jsonResponse(429, {}));
    render(<ChatView initial={[]} />);
    typeAndSend();

    expect(
      await screen.findByText("You've reached this month's AI limit — chat resumes next month."),
    ).toBeInTheDocument();
  });

  it("still treats a plain 402 as subscription_required (no cap banner, no crash)", async () => {
    streamChatMock.mockResolvedValue(jsonResponse(402, { error: "subscription_required" }));
    render(<ChatView initial={[]} />);
    typeAndSend();

    // The failed bubble appears; no cap banner and no generic error banner.
    expect(await screen.findByText("Dismiss")).toBeInTheDocument();
    expect(screen.queryByText(/AI limit/)).toBeNull();
  });
});

describe("ChatView — provider brownout (P1-10)", () => {
  it("shows the waiting-for-capacity copy when the terminal SSE error is rate_limited", async () => {
    streamChatMock.mockResolvedValue(
      sseResponse([
        'event: ack\ndata: {"user_message_id":1}\n\n',
        'event: error\ndata: {"code":"rate_limited"}\n\n',
      ]),
    );
    render(<ChatView initial={[]} />);
    typeAndSend();

    expect(
      await screen.findByText("The AI service is busy — waiting for capacity. Try again in a moment."),
    ).toBeInTheDocument();
    // Retryable — the banner DOES offer Try again.
    expect(screen.getByText("Try again")).toBeInTheDocument();
    expect(screen.queryByText("The assistant couldn't finish that reply.")).toBeNull();
  });

  it("flips the phase line to waiting after a stall, then fails as rate_limited on drop", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const enc = new TextEncoder();
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const stream = new ReadableStream<Uint8Array>({
      start(c) {
        controller = c;
      },
    });
    streamChatMock.mockResolvedValue(
      new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } }),
    );

    render(<ChatView initial={[]} />);
    typeAndSend();
    await flush(); // reach parseSseStream + arm the watchdog

    act(() => {
      controller.enqueue(enc.encode("event: ack\ndata: {}\n\n"));
    });
    await flush();

    // Silence past the watchdog → waiting copy instead of eternal "Thinking…".
    act(() => {
      vi.advanceTimersByTime(CHAT_STALL_MS + 1);
    });
    expect(screen.getByText("Waiting for AI capacity…")).toBeInTheDocument();

    // The stalled stream then drops without a terminal event → rate_limited
    // (retryable), NOT the generic agent error.
    act(() => {
      controller.close();
    });
    await flush();
    expect(
      screen.getByText("The AI service is busy — waiting for capacity. Try again in a moment."),
    ).toBeInTheDocument();
    expect(screen.queryByText("The assistant couldn't finish that reply.")).toBeNull();
  });
});

describe("ChatView — subscription tri-state (P1-11)", () => {
  it("renders the neutral unavailable panel on subscription error — never the upsell", () => {
    render(<ChatView initial={[]} pro={false} unavailable />);
    expect(screen.getByText("Chat is unavailable right now.")).toBeInTheDocument();
    expect(screen.queryByText("Upgrade to Pro")).toBeNull();
    expect(screen.queryByText(/Pro feature/)).toBeNull();
    // No composer, no Pro-badged suggestion chips.
    expect(screen.queryByLabelText("Ask a question about your claim")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("still upsells a CONFIRMED free user (control)", () => {
    render(<ChatView initial={[]} pro={false} />);
    expect(screen.getByText("Upgrade to Pro")).toBeInTheDocument();
    expect(screen.queryByText("Chat is unavailable right now.")).toBeNull();
  });
});
