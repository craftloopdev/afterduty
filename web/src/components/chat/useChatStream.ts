"use client";

import { useCallback, useReducer, useRef } from "react";
import { sendChat, streamChat } from "@/lib/api/mutations";
import { parseSseStream } from "@/lib/sse";
import { detectUsageLimit } from "./usageLimit";
import type { MessageVM } from "@/lib/models/vm";
import { toMessage } from "@/lib/adapters/message";
import type { MessageResponse } from "@/lib/models/api";

// Client transport for chat (spec §F.4). State machine: idle → sending →
// streaming → done | failed. `send(text)` POSTs /api/chat/stream and consumes
// the SSE wire contract (ack / status / delta / message / error). On 409
// (streaming_disabled), 404 (old backend), or any network/parse failure BEFORE
// the first delta, it falls back to the legacy non-streaming POST /api/chat
// (full-thread refetch). A failure AFTER deltas → failed state with retry (no
// silent re-send). 402 surfaces subscription_required to the gate UI; the
// monthly usage cap (429 or body `USAGE_LIMIT_REACHED` — P1-10) surfaces as
// `usage_limit` with the reset date, distinct from both.

export type SendStatus = "idle" | "sending" | "streaming" | "done" | "failed";

type Phase = "thinking" | "tool" | "waiting" | null;

/** Silence on the open stream longer than this flips the phase line to the
 *  "waiting for capacity" copy (provider brownouts stall ~90s — P1-10). */
export const CHAT_STALL_MS = 20_000;

export interface ChatStreamState {
  messages: MessageVM[];
  status: SendStatus;
  /** Current agent phase line driver. */
  phase: Phase;
  /** Tool name when phase === "tool". */
  toolName: string | null;
  /** Non-null when the send failed; drives the error banner. */
  error: ChatError | null;
  /** id of the in-flight optimistic user bubble (for retry/dismiss). */
  pendingUserId: string | null;
  /** id of the in-flight assistant bubble accumulating deltas. */
  streamingAssistantId: string | null;
}

export type ChatError =
  | { kind: "subscription_required" }
  | { kind: "agent_error" }
  | { kind: "rate_limited" }
  /** Monthly AI cap (P1-10): terminal until `resumesAt` — the UI must NOT offer retry. */
  | { kind: "usage_limit"; resumesAt?: string }
  | { kind: "generic" };

type Action =
  | { type: "send_start"; userId: string; text: string }
  | { type: "ack"; serverUserId?: number }
  | { type: "status"; phase: Phase; toolName: string | null }
  | { type: "delta"; text: string }
  | { type: "assistant_done"; message: MessageVM }
  | { type: "thread_replace"; messages: MessageVM[] }
  | { type: "fail_before_ack"; userId: string; error: ChatError }
  | { type: "fail_after_ack"; error: ChatError }
  | { type: "stall" }
  | { type: "retry"; userId: string }
  | { type: "dismiss"; userId: string };

export const initialChatState = (initial: MessageVM[]): ChatStreamState => ({
  messages: initial,
  status: "idle",
  phase: null,
  toolName: null,
  error: null,
  pendingUserId: null,
  streamingAssistantId: null,
});

/**
 * Pure reducer — the whole send lifecycle, unit-testable without fetch/DOM.
 */
export function chatReducer(state: ChatStreamState, action: Action): ChatStreamState {
  switch (action.type) {
    case "send_start": {
      const userBubble: MessageVM = {
        id: action.userId,
        role: "user",
        content: action.text,
        pending: true,
      };
      const assistantId = `tmp-stream-${action.userId}`;
      return {
        ...state,
        // Drop any prior failed bubble for the same retry id, then append fresh.
        messages: [
          ...state.messages.filter((m) => m.id !== action.userId),
          userBubble,
          { id: assistantId, role: "assistant", content: "", pending: true },
        ],
        status: "sending",
        phase: "thinking",
        toolName: null,
        error: null,
        pendingUserId: action.userId,
        streamingAssistantId: assistantId,
      };
    }
    case "ack": {
      return {
        ...state,
        status: "streaming",
        messages: state.messages.map((m) =>
          m.id === state.pendingUserId ? { ...m, pending: false } : m,
        ),
      };
    }
    case "status":
      return { ...state, phase: action.phase, toolName: action.toolName };
    case "delta": {
      if (!state.streamingAssistantId) return state;
      return {
        ...state,
        status: "streaming",
        messages: state.messages.map((m) =>
          m.id === state.streamingAssistantId
            ? { ...m, content: m.content + action.text }
            : m,
        ),
      };
    }
    case "assistant_done": {
      return {
        ...state,
        status: "done",
        phase: null,
        toolName: null,
        pendingUserId: null,
        streamingAssistantId: null,
        messages: state.messages.map((m) =>
          m.id === state.streamingAssistantId
            ? { ...action.message, pending: false }
            : m,
        ),
      };
    }
    case "thread_replace": {
      // Non-streaming fallback resolved with the canonical thread.
      return {
        ...state,
        status: "done",
        phase: null,
        toolName: null,
        pendingUserId: null,
        streamingAssistantId: null,
        error: null,
        messages: action.messages,
      };
    }
    case "fail_before_ack": {
      // Nothing persisted server-side: drop the placeholder assistant bubble,
      // mark the user bubble failed so it keeps the text + offers retry.
      return {
        ...state,
        status: "failed",
        phase: null,
        toolName: null,
        streamingAssistantId: null,
        pendingUserId: null,
        error: action.error,
        messages: state.messages
          .filter((m) => m.id !== state.streamingAssistantId)
          .map((m) =>
            m.id === action.userId ? { ...m, pending: false, failed: true } : m,
          ),
      };
    }
    case "fail_after_ack": {
      // User msg IS persisted; the reply failed. Keep the user bubble, drop the
      // empty assistant placeholder, show the banner with a "Try again".
      return {
        ...state,
        status: "failed",
        phase: null,
        toolName: null,
        streamingAssistantId: null,
        pendingUserId: null,
        error: action.error,
        messages: state.messages.filter(
          (m) => !(m.id === state.streamingAssistantId && !m.content),
        ),
      };
    }
    case "stall": {
      // The open stream has gone silent past CHAT_STALL_MS — show the
      // "waiting for capacity" phase line instead of an eternal "Thinking…".
      if (state.status !== "sending" && state.status !== "streaming") return state;
      return { ...state, phase: "waiting", toolName: null };
    }
    case "dismiss":
      return {
        ...state,
        status: "idle",
        error: null,
        messages: state.messages.filter((m) => m.id !== action.userId),
      };
    case "retry":
      // Handled by the hook (it re-calls send); reducer just clears the error.
      return { ...state, error: null };
    default:
      return state;
  }
}

interface UseChatStreamResult {
  state: ChatStreamState;
  send: (text: string) => Promise<void>;
  retry: (userId: string, text: string) => void;
  dismiss: (userId: string) => void;
}

const PERSISTED = (id: string) => !id.startsWith("tmp-");

export function useChatStream(initial: MessageVM[]): UseChatStreamResult {
  const [state, dispatch] = useReducer(chatReducer, initial, initialChatState);
  // rAF-batched delta coalescing so markdown re-renders at most once per frame.
  const pendingDelta = useRef("");
  const rafId = useRef<number | null>(null);

  const flushDelta = useCallback(() => {
    rafId.current = null;
    if (pendingDelta.current) {
      const text = pendingDelta.current;
      pendingDelta.current = "";
      dispatch({ type: "delta", text });
    }
  }, []);

  const queueDelta = useCallback(
    (text: string) => {
      pendingDelta.current += text;
      if (rafId.current == null) {
        rafId.current =
          typeof requestAnimationFrame === "function"
            ? requestAnimationFrame(flushDelta)
            : (setTimeout(flushDelta, 16) as unknown as number);
      }
    },
    [flushDelta],
  );

  // Legacy non-streaming POST → full-thread refetch (the fallback path).
  const sendViaPost = useCallback(async (text: string, userId: string): Promise<void> => {
    try {
      const res = await sendChat(text);
      if (!res.ok) {
        let body: unknown = null;
        try {
          body = await res.json();
        } catch {
          body = null;
        }
        // Monthly cap first (P1-10): a 402 whose body carries USAGE_LIMIT_REACHED
        // is the cap, NOT a missing subscription — never show pro users the gate.
        const cap = detectUsageLimit(res.status, body);
        if (cap) {
          dispatch({
            type: "fail_before_ack",
            userId,
            error: { kind: "usage_limit", resumesAt: cap.resumesAt },
          });
          return;
        }
        if (res.status === 402) {
          dispatch({ type: "fail_before_ack", userId, error: { kind: "subscription_required" } });
          return;
        }
        dispatch({ type: "fail_before_ack", userId, error: { kind: "generic" } });
        return;
      }
      const updated = (await res.json()) as MessageResponse[] | MessageVM[];
      const messages = Array.isArray(updated)
        ? updated.map((m, i) =>
            "role" in m && typeof (m as MessageVM).id === "string" && "content" in m
              ? (m as MessageVM)
              : toMessage(m as MessageResponse, i),
          )
        : [];
      dispatch({ type: "thread_replace", messages });
    } catch {
      dispatch({ type: "fail_before_ack", userId, error: { kind: "generic" } });
    }
  }, []);

  const send = useCallback(
    async (text: string): Promise<void> => {
      const trimmed = text.trim();
      if (!trimmed) return;
      const userId = `tmp-${crypto.randomUUID()}`;
      dispatch({ type: "send_start", userId, text: trimmed });

      let acked = false;
      let sawDelta = false;
      let terminal = false; // saw a `message` or `error` terminal event

      let res: Response;
      try {
        res = await streamChat(trimmed);
      } catch {
        // Network error before any byte → safe to fall back to POST.
        await sendViaPost(trimmed, userId);
        return;
      }

      const ct = res.headers.get("content-type") ?? "";
      const isSse = res.ok && ct.includes("text/event-stream");

      if (!isSse) {
        // Cap (429 OR body USAGE_LIMIT_REACHED) → terminal banner; 402 → gate;
        // 409/404 → fallback to non-streaming POST; others → fail.
        let body: unknown = null;
        try {
          body = await res.json();
        } catch {
          body = null;
        }
        const cap = detectUsageLimit(res.status, body);
        if (cap) {
          dispatch({
            type: "fail_before_ack",
            userId,
            error: { kind: "usage_limit", resumesAt: cap.resumesAt },
          });
          return;
        }
        if (res.status === 402) {
          dispatch({ type: "fail_before_ack", userId, error: { kind: "subscription_required" } });
          return;
        }
        if (res.status === 409 || res.status === 404) {
          await sendViaPost(trimmed, userId);
          return;
        }
        dispatch({ type: "fail_before_ack", userId, error: { kind: "generic" } });
        return;
      }

      if (!res.body) {
        await sendViaPost(trimmed, userId);
        return;
      }

      const reader = res.body.getReader();
      // Stall watchdog (P1-10): provider brownouts leave the stream open but
      // silent for ~90s. After CHAT_STALL_MS without an event, flip the phase
      // line to the "waiting for capacity" copy; if the stream then dies
      // without a terminal event, classify as rate_limited (retryable), not
      // the generic agent error.
      let stalled = false;
      let stallId: ReturnType<typeof setTimeout> | null = null;
      const clearStall = () => {
        if (stallId != null) {
          clearTimeout(stallId);
          stallId = null;
        }
      };
      const armStall = () => {
        clearStall();
        stallId = setTimeout(() => {
          stalled = true;
          dispatch({ type: "stall" });
        }, CHAT_STALL_MS);
      };
      const stallKind = (): ChatError => (stalled ? { kind: "rate_limited" } : { kind: "agent_error" });
      armStall();
      try {
        await parseSseStream(reader, (e) => {
          stalled = false;
          armStall();
          let data: Record<string, unknown> = {};
          try {
            data = e.data ? (JSON.parse(e.data) as Record<string, unknown>) : {};
          } catch {
            data = {};
          }
          switch (e.event) {
            case "ack":
              acked = true;
              dispatch({ type: "ack", serverUserId: data.user_message_id as number | undefined });
              break;
            case "status":
              dispatch({
                type: "status",
                phase: (data.phase as Phase) ?? "thinking",
                toolName: (data.tool as string) ?? null,
              });
              break;
            case "delta": {
              sawDelta = true;
              const t = typeof data.text === "string" ? data.text : "";
              if (t) queueDelta(t);
              break;
            }
            case "message": {
              terminal = true;
              if (rafId.current != null) flushDelta();
              const vm = toMessage(data as unknown as MessageResponse, 0);
              dispatch({ type: "assistant_done", message: vm });
              break;
            }
            case "error": {
              terminal = true;
              // Monthly cap can also arrive as a terminal SSE event; a
              // retryable classification (rate_limited / retryable:true) keeps
              // the "waiting for capacity" copy over the generic agent error.
              const cap = detectUsageLimit(0, data);
              const err: ChatError = cap
                ? { kind: "usage_limit", resumesAt: cap.resumesAt }
                : data.code === "rate_limited" || data.retryable === true
                  ? { kind: "rate_limited" }
                  : { kind: "agent_error" };
              if (acked) dispatch({ type: "fail_after_ack", error: err });
              else dispatch({ type: "fail_before_ack", userId, error: err });
              break;
            }
          }
        });
        clearStall();
        // Stream closed without a terminal event (rare proxy cut mid-turn).
        if (!terminal) {
          if (rafId.current != null) flushDelta();
          if (acked) {
            // User msg persisted; reply incomplete — offer retry.
            dispatch({ type: "fail_after_ack", error: stallKind() });
          } else if (!sawDelta && !stalled) {
            // Nothing happened — fall back to the non-streaming POST.
            await sendViaPost(trimmed, userId);
          } else {
            // A stalled-then-dropped stream is a capacity problem: re-POSTing
            // immediately would hit the same brownout — surface it instead.
            dispatch({ type: "fail_before_ack", userId, error: stallKind() });
          }
        }
      } catch {
        clearStall();
        if (rafId.current != null) flushDelta();
        // Parse/read failure: before any delta → fall back to POST; after → fail.
        if (!sawDelta && !acked && !stalled) {
          await sendViaPost(trimmed, userId);
        } else if (acked) {
          dispatch({ type: "fail_after_ack", error: stallKind() });
        } else {
          dispatch({ type: "fail_before_ack", userId, error: stallKind() });
        }
      } finally {
        clearStall();
      }
    },
    [flushDelta, queueDelta, sendViaPost],
  );

  const retry = useCallback(
    (userId: string, text: string) => {
      dispatch({ type: "retry", userId });
      // Drop the failed bubble (send_start filters by id, but the new id differs).
      dispatch({ type: "dismiss", userId });
      void send(text);
    },
    [send],
  );

  const dismiss = useCallback((userId: string) => {
    dispatch({ type: "dismiss", userId });
  }, []);

  // Keep PERSISTED referenced so tree-shaking doesn't flag it; it documents the
  // tmp- vs server id distinction used across the reducer.
  void PERSISTED;

  return { state, send, retry, dismiss };
}
