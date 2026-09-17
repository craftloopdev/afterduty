// Pure, incremental Server-Sent-Events parser. No DOM, no fetch — it takes a
// byte-stream reader and emits decoded {event, data} records as they complete.
// Used by useChatStream to consume the BFF passthrough of the Spring SSE wire
// contract (ack / status / delta / message / error). Comment lines (`: ping`
// heartbeats) are skipped. CRLF- and multi-`data:`-tolerant per the SSE spec.

export interface SseEvent {
  /** The `event:` name, or "message" when omitted (SSE default). */
  event: string;
  /** Joined `data:` payload (multiple data lines joined with "\n"). */
  data: string;
}

/**
 * Parse a stream of SSE bytes, invoking `onEvent` once per dispatched event.
 * Resolves when the underlying reader is exhausted. A trailing event without a
 * terminating blank line is flushed on close (some servers omit it).
 */
export async function parseSseStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onEvent: (e: SseEvent) => void,
): Promise<void> {
  const decoder = new TextDecoder();
  let buffer = "";

  const flushFrame = (frame: string) => {
    const ev = parseFrame(frame);
    if (ev) onEvent(ev);
  };

  // Split on the blank-line frame boundary. SSE frames end with \n\n (or \r\n\r\n).
  const drain = () => {
    let idx: number;
    // Normalize CRLF to LF as frames complete so the boundary search is simple.
    while ((idx = frameBoundary(buffer)) !== -1) {
      const frame = buffer.slice(0, idx.valueOf());
      buffer = buffer.slice(boundaryEnd(buffer, idx));
      flushFrame(frame);
    }
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    drain();
  }
  buffer += decoder.decode();
  drain();
  // Flush any dangling frame that lacked a trailing blank line.
  if (buffer.trim().length > 0) flushFrame(buffer);
}

/** Index of the first frame boundary (blank line), or -1. */
function frameBoundary(buf: string): number {
  const lf = buf.indexOf("\n\n");
  const crlf = buf.indexOf("\r\n\r\n");
  if (lf === -1) return crlf;
  if (crlf === -1) return lf;
  return Math.min(lf, crlf);
}

/** Length of the boundary delimiter at `idx` (2 for \n\n, 4 for \r\n\r\n). */
function boundaryEnd(buf: string, idx: number): number {
  return buf.startsWith("\r\n\r\n", idx) ? idx + 4 : idx + 2;
}

/**
 * Parse one frame (the text between boundaries) into an SseEvent.
 * Returns null for comment-only / empty frames (e.g. `: ping` heartbeats).
 */
export function parseFrame(frame: string): SseEvent | null {
  let event = "message";
  const dataLines: string[] = [];
  let sawData = false;

  for (const rawLine of frame.split("\n")) {
    const line = rawLine.replace(/\r$/, "");
    if (line === "" || line.startsWith(":")) continue; // comment / blank
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    // Per spec: strip a single leading space after the colon.
    let val = colon === -1 ? "" : line.slice(colon + 1);
    if (val.startsWith(" ")) val = val.slice(1);

    if (field === "event") {
      event = val;
    } else if (field === "data") {
      dataLines.push(val);
      sawData = true;
    }
    // id/retry fields are ignored for this contract.
  }

  if (!sawData) return null;
  return { event, data: dataLines.join("\n") };
}
