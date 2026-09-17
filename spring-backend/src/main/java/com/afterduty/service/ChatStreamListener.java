package com.afterduty.service;

/**
 * Sink for the incremental events the {@link ChatAgent} emits while it streams a reply
 * (Increment 7 §F.2). The streaming controller (ChatStreamController) supplies an
 * implementation that forwards each callback onto the SSE wire as {@code delta} /
 * {@code status} events; the non-streaming {@link ChatAgent#handle} path supplies
 * {@link #NOOP} so there is exactly ONE agent code path.
 *
 * <p>Implementations must be cheap and must never throw back into the agent loop — a
 * client disconnect makes the underlying emitter sends fail, and the agent must keep
 * running so the full reply still persists ({@code completeTurn} on disconnect). The
 * controller's adapter swallows its own send failures.
 */
public interface ChatStreamListener {

    /** A chunk of assistant text was produced; append in order. */
    void onDelta(String text);

    /**
     * The agent changed phase. {@code phase} is {@code "thinking"} at the start of each
     * model round-trip, or {@code "tool"} when a tool-use block begins (with
     * {@code toolName} set to the tool, e.g. {@code search_my_file}). {@code toolName}
     * is null for non-tool phases.
     */
    void onStatus(String phase, String toolName);

    /** Drop-all sink for the non-streaming path. */
    ChatStreamListener NOOP = new ChatStreamListener() {
        @Override public void onDelta(String text) { }
        @Override public void onStatus(String phase, String toolName) { }
    };
}
