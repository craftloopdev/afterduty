import { describe, it, expect } from "vitest";
import { chatReducer, initialChatState, type ChatStreamState } from "./useChatStream";
import type { MessageVM } from "@/lib/models/vm";

const seed: MessageVM[] = [{ id: "h1", role: "assistant", content: "prior" }];

function start(): ChatStreamState {
  return chatReducer(initialChatState(seed), {
    type: "send_start",
    userId: "tmp-u1",
    text: "hello",
  });
}

describe("chatReducer", () => {
  it("send_start appends an optimistic user bubble + empty assistant placeholder", () => {
    const s = start();
    expect(s.status).toBe("sending");
    expect(s.phase).toBe("thinking");
    expect(s.pendingUserId).toBe("tmp-u1");
    const user = s.messages.find((m) => m.id === "tmp-u1");
    expect(user).toMatchObject({ role: "user", content: "hello", pending: true });
    expect(s.messages.some((m) => m.id === s.streamingAssistantId && m.role === "assistant")).toBe(true);
  });

  it("ack clears the pending flag on the user bubble and moves to streaming", () => {
    const s = chatReducer(start(), { type: "ack", serverUserId: 9 });
    expect(s.status).toBe("streaming");
    expect(s.messages.find((m) => m.id === "tmp-u1")?.pending).toBe(false);
  });

  it("delta appends text to the in-flight assistant bubble", () => {
    let s = chatReducer(start(), { type: "ack" });
    s = chatReducer(s, { type: "delta", text: "Hel" });
    s = chatReducer(s, { type: "delta", text: "lo" });
    expect(s.messages.find((m) => m.id === s.streamingAssistantId)?.content).toBe("Hello");
  });

  it("assistant_done replaces the placeholder with canonical content", () => {
    let s = chatReducer(start(), { type: "ack" });
    s = chatReducer(s, { type: "delta", text: "partial" });
    s = chatReducer(s, {
      type: "assistant_done",
      message: { id: "55", role: "assistant", content: "final answer" },
    });
    expect(s.status).toBe("done");
    expect(s.streamingAssistantId).toBeNull();
    expect(s.messages.find((m) => m.id === "55")?.content).toBe("final answer");
    expect(s.messages.some((m) => m.id.startsWith("tmp-stream-"))).toBe(false);
  });

  it("fail_before_ack marks the user bubble failed and drops the placeholder", () => {
    const s = chatReducer(start(), {
      type: "fail_before_ack",
      userId: "tmp-u1",
      error: { kind: "generic" },
    });
    expect(s.status).toBe("failed");
    expect(s.messages.find((m) => m.id === "tmp-u1")).toMatchObject({ failed: true, pending: false });
    expect(s.messages.some((m) => m.id.startsWith("tmp-stream-"))).toBe(false);
    expect(s.error).toEqual({ kind: "generic" });
  });

  it("fail_after_ack keeps the user bubble (persisted) and drops the empty placeholder", () => {
    let s = chatReducer(start(), { type: "ack" });
    s = chatReducer(s, { type: "fail_after_ack", error: { kind: "agent_error" } });
    expect(s.status).toBe("failed");
    expect(s.messages.find((m) => m.id === "tmp-u1")?.failed).toBeUndefined();
    expect(s.messages.some((m) => m.id.startsWith("tmp-stream-") && m.content === "")).toBe(false);
  });

  it("thread_replace swaps in the canonical thread (fallback path)", () => {
    const replaced: MessageVM[] = [
      { id: "1", role: "user", content: "hello" },
      { id: "2", role: "assistant", content: "hi back" },
    ];
    const s = chatReducer(start(), { type: "thread_replace", messages: replaced });
    expect(s.status).toBe("done");
    expect(s.messages).toEqual(replaced);
  });

  it("dismiss removes the failed user bubble and clears error", () => {
    let s = chatReducer(start(), { type: "fail_before_ack", userId: "tmp-u1", error: { kind: "generic" } });
    s = chatReducer(s, { type: "dismiss", userId: "tmp-u1" });
    expect(s.messages.some((m) => m.id === "tmp-u1")).toBe(false);
    expect(s.error).toBeNull();
    expect(s.status).toBe("idle");
  });

  it("status updates the phase + tool name", () => {
    const s = chatReducer(start(), { type: "status", phase: "tool", toolName: "search_my_file" });
    expect(s.phase).toBe("tool");
    expect(s.toolName).toBe("search_my_file");
  });

  // P1-10: the stall watchdog flips the phase line to "waiting" so a provider
  // brownout shows the waiting-for-capacity copy instead of eternal "Thinking…".
  it("stall flips the phase to waiting while a send is in flight", () => {
    let s = chatReducer(start(), { type: "stall" });
    expect(s.phase).toBe("waiting");
    s = chatReducer(chatReducer(start(), { type: "ack" }), { type: "stall" });
    expect(s.phase).toBe("waiting");
    expect(s.status).toBe("streaming");
  });

  it("stall is a no-op when no send is in flight", () => {
    const idle = initialChatState(seed);
    expect(chatReducer(idle, { type: "stall" })).toBe(idle);
    const failed = chatReducer(start(), {
      type: "fail_before_ack",
      userId: "tmp-u1",
      error: { kind: "generic" },
    });
    expect(chatReducer(failed, { type: "stall" }).phase).toBeNull();
  });

  // P1-10: the usage-cap error carries the reset date through to the banner.
  it("fail_after_ack retains the usage_limit resumesAt payload", () => {
    let s = chatReducer(start(), { type: "ack" });
    s = chatReducer(s, {
      type: "fail_after_ack",
      error: { kind: "usage_limit", resumesAt: "2026-08-01T00:00:00Z" },
    });
    expect(s.status).toBe("failed");
    expect(s.error).toEqual({ kind: "usage_limit", resumesAt: "2026-08-01T00:00:00Z" });
  });
});
