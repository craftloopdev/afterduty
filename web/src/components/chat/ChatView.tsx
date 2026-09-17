"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import type { MessageVM } from "@/lib/models/vm";
import { Markdown } from "./Markdown";
import { useChatStream, type ChatError } from "./useChatStream";
import { usageLimitCopy } from "./usageLimit";
import styles from "./ChatView.module.css";

const SUGGESTIONS = [
  "What's the strongest part of my claim?",
  "What evidence should I add next?",
  "Which conditions are ready to file?",
];

const STATUS_COPY: Record<string, string> = {
  thinking: "Thinking…",
  waiting: "Waiting for AI capacity…",
  search_my_file: "Searching your documents…",
  vasrd_lookup: "Checking the rating schedule…",
  get_analysis: "Reviewing your analysis…",
  add_atom: "Updating your claim…",
  update_atom: "Updating your claim…",
  delete_atom: "Updating your claim…",
  update_condition: "Updating your claim…",
  delete_condition: "Updating your claim…",
  mark_gap_resolved: "Updating your claim…",
  mark_gap_dismissed: "Updating your claim…",
};

const ERROR_COPY: Record<Exclude<ChatError["kind"], "usage_limit">, string> = {
  subscription_required: "Ask AI needs an active Pro subscription.",
  agent_error: "The assistant couldn't finish that reply.",
  rate_limited: "The AI service is busy — waiting for capacity. Try again in a moment.",
  generic: "Couldn't get a response. Please try again.",
};

/** Banner copy per error — the cap (P1-10) interpolates the humanized reset date. */
function errorText(error: ChatError): string {
  return error.kind === "usage_limit" ? usageLimitCopy(error.resumesAt) : ERROR_COPY[error.kind];
}

const MAX_TEXTAREA_PX = 150; // ~6 rows

function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

export interface ChatViewProps {
  initial: MessageVM[];
  /** From the layout's isPro — gates composing without firing a request. */
  pro?: boolean;
  /**
   * Tri-state subscription read came back "error" (P1-11): we could NOT
   * confirm the plan, so render a neutral outage panel — NEVER the Pro upsell
   * (the viewer may well be a paying subscriber).
   */
  unavailable?: boolean;
  /** Optional composer prefill (e.g. from ConditionDetail "Ask AI about this"). */
  prefill?: string;
}

export function ChatView({ initial, pro = true, unavailable = false, prefill }: ChatViewProps) {
  const { state, send, retry, dismiss } = useChatStream(initial);
  const { messages, status, phase, toolName, error } = state;
  const [input, setInput] = useState(prefill ?? "");

  const threadRef = useRef<HTMLDivElement>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const nearBottom = useRef(true);

  const busy = status === "sending" || status === "streaming";

  // Track whether the user is near the bottom (so we don't yank them up).
  useEffect(() => {
    const el = threadRef.current;
    if (!el) return;
    const onScroll = () => {
      nearBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, []);

  // Auto-follow only when already near the bottom; respect reduced motion.
  useLayoutEffect(() => {
    if (!nearBottom.current) return;
    endRef.current?.scrollIntoView({
      behavior: prefersReducedMotion() ? "auto" : "smooth",
      block: "end",
    });
  }, [messages, phase, busy]);

  // Auto-grow the textarea.
  const autoGrow = () => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, MAX_TEXTAREA_PX)}px`;
  };
  useEffect(autoGrow, [input]);

  // Return focus to the composer after each completed/failed turn.
  useEffect(() => {
    if ((status === "done" || status === "failed") && pro) taRef.current?.focus();
  }, [status, pro]);

  const submit = (text: string) => {
    const msg = text.trim();
    if (!msg || busy) return;
    setInput(""); // cleared optimistically; the failed bubble retains the text
    void send(msg);
  };

  const phaseLabel =
    phase === "tool" && toolName
      ? STATUS_COPY[toolName] ?? "Working…"
      : phase === "waiting"
        ? STATUS_COPY.waiting
        : STATUS_COPY.thinking;
  const empty = messages.length === 0;
  // At the monthly cap retry CANNOT succeed until the reset — offer no retry
  // affordance anywhere (banner or failed bubble), only Dismiss (P1-10).
  const atCap = error?.kind === "usage_limit";

  // ── Locked (free) state ─────────────────────────────────────────────
  const lockedPanel = (
    <div className={styles.locked}>
      <span className={styles.lockedIc}>
        <Icon name="lock" size={22} />
      </span>
      <b>Ask AI is a Pro feature.</b>
      <p>Get answers grounded in your own records and the VA rating schedule, with citations.</p>
      <Link href="/upgrade" className={styles.lockedCta}>
        <Button variant="primary" icon="sparkle">
          Upgrade to Pro
        </Button>
      </Link>
    </div>
  );

  // ── Subscription unknown (outage) state — neutral, never an upsell (P1-11) ──
  const unavailablePanel = (
    <div className={styles.locked} role="status">
      <span className={styles.lockedIc}>
        <Icon name="info" size={22} />
      </span>
      <b>Chat is unavailable right now.</b>
      <p>
        We couldn&apos;t confirm your subscription status. Your plan hasn&apos;t changed —
        please try again in a few minutes.
      </p>
    </div>
  );

  return (
    <div className={styles.wrap}>
      <div
        className={styles.thread}
        ref={threadRef}
        role="log"
        aria-live="polite"
        aria-label="Conversation"
      >
        {empty && (
          <div className={styles.intro}>
            <span className={styles.introIc}>
              <Icon name="sparkle" size={26} />
            </span>
            <h2>Ask about your claim</h2>
            <p>AI-assisted &amp; educational — not legal advice.</p>
            <div className={styles.suggest}>
              {/* During a subscription outage the chips must not upsell (P1-11). */}
              {!unavailable &&
                SUGGESTIONS.map((s) =>
                  pro ? (
                    <button key={s} type="button" className={styles.chip} onClick={() => submit(s)}>
                      {s}
                    </button>
                  ) : (
                    <Link key={s} href="/upgrade" className={styles.chip}>
                      {s}
                      <span className={styles.proBadge}>Pro</span>
                    </Link>
                  ),
                )}
            </div>
          </div>
        )}

        {messages.map((m) => {
          const isStreaming = m.role === "assistant" && m.pending;
          return (
            <div key={m.id} className={styles.row} data-role={m.role}>
              <div
                className={[styles.bubble, m.failed && styles.bubbleFailed].filter(Boolean).join(" ")}
                data-role={m.role}
              >
                <span className="visually-hidden">{m.role === "user" ? "You: " : "AI: "}</span>
                {m.role === "assistant" ? (
                  // Deltas accumulate inside aria-hidden; the canonical (non-
                  // pending) bubble is announced once on completion.
                  <span aria-hidden={isStreaming || undefined}>
                    {m.content ? <Markdown>{m.content}</Markdown> : null}
                    {isStreaming && !m.content && <span className={styles.phase}>{phaseLabel}</span>}
                  </span>
                ) : (
                  m.content
                )}
              </div>
              {m.failed && m.role === "user" && (
                <div className={styles.failActions}>
                  {!atCap && (
                    <button type="button" className={styles.failBtn} onClick={() => retry(m.id, m.content)}>
                      <Icon name="back" size={14} stroke={2.3} /> Retry
                    </button>
                  )}
                  <button type="button" className={styles.failBtnGhost} onClick={() => dismiss(m.id)}>
                    Dismiss
                  </button>
                </div>
              )}
            </div>
          );
        })}

        <div ref={endRef} />
      </div>

      {/* Monthly cap (P1-10): a distinct banner with NO retry — retrying
          cannot succeed until the reset date. */}
      {error && error.kind === "usage_limit" && (
        <div className={styles.cap} role="alert">
          <Icon name="clock" size={16} />
          <span>{errorText(error)}</span>
        </div>
      )}

      {error && error.kind !== "subscription_required" && error.kind !== "usage_limit" && (
        <div className={styles.error} role="alert">
          <span>{errorText(error)}</span>
          {status === "failed" && (
            <button type="button" className={styles.errorRetry} onClick={() => submit(lastUserText(messages))}>
              Try again
            </button>
          )}
        </div>
      )}

      {unavailable ? (
        unavailablePanel
      ) : pro ? (
        <form
          className={styles.composer}
          onSubmit={(e) => {
            e.preventDefault();
            submit(input);
          }}
        >
          <textarea
            ref={taRef}
            className={styles.input}
            rows={1}
            placeholder="Ask about your conditions, evidence, or ratings…"
            aria-label="Ask a question about your claim"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                submit(input);
              }
            }}
          />
          <Button type="submit" variant="primary" icon="send" loading={busy} disabled={!input.trim() || busy}>
            Send
          </Button>
        </form>
      ) : (
        lockedPanel
      )}
    </div>
  );
}

function lastUserText(messages: MessageVM[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "user") return messages[i].content;
  }
  return "";
}
