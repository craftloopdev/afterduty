import { describe, it, expect } from "vitest";
import { parseFrame, parseSseStream, type SseEvent } from "./sse";

function streamFromChunks(chunks: string[]): ReadableStreamDefaultReader<Uint8Array> {
  const enc = new TextEncoder();
  let i = 0;
  return {
    read() {
      if (i < chunks.length) {
        return Promise.resolve({ value: enc.encode(chunks[i++]), done: false });
      }
      return Promise.resolve({ value: undefined, done: true });
    },
    releaseLock() {},
    cancel() {
      return Promise.resolve();
    },
    get closed() {
      return Promise.resolve(undefined);
    },
  } as unknown as ReadableStreamDefaultReader<Uint8Array>;
}

async function collect(chunks: string[]): Promise<SseEvent[]> {
  const out: SseEvent[] = [];
  await parseSseStream(streamFromChunks(chunks), (e) => out.push(e));
  return out;
}

describe("parseFrame", () => {
  it("parses event + data", () => {
    expect(parseFrame("event: ack\ndata: {\"user_message_id\":7}")).toEqual({
      event: "ack",
      data: '{"user_message_id":7}',
    });
  });
  it("defaults event to 'message' when omitted", () => {
    expect(parseFrame("data: hi")).toEqual({ event: "message", data: "hi" });
  });
  it("joins multiple data lines with newline", () => {
    expect(parseFrame("event: delta\ndata: a\ndata: b")).toEqual({ event: "delta", data: "a\nb" });
  });
  it("returns null for comment-only frames (heartbeats)", () => {
    expect(parseFrame(": ping")).toBeNull();
  });
  it("strips a single leading space after the colon", () => {
    expect(parseFrame("data:  two-spaces")?.data).toBe(" two-spaces");
  });
});

describe("parseSseStream", () => {
  it("dispatches events split across arbitrary chunk boundaries", async () => {
    const events = await collect(["event: ack\nda", "ta: {}\n\neve", "nt: delta\ndata: hi\n\n"]);
    expect(events).toEqual([
      { event: "ack", data: "{}" },
      { event: "delta", data: "hi" },
    ]);
  });

  it("is CRLF tolerant", async () => {
    const events = await collect(["event: status\r\ndata: {\"phase\":\"thinking\"}\r\n\r\n"]);
    expect(events).toEqual([{ event: "status", data: '{"phase":"thinking"}' }]);
  });

  it("skips heartbeat comment frames", async () => {
    const events = await collect([": ping\n\n", "event: delta\ndata: x\n\n", ": ping\n\n"]);
    expect(events).toEqual([{ event: "delta", data: "x" }]);
  });

  it("flushes a trailing frame with no terminating blank line", async () => {
    const events = await collect(["event: message\ndata: {\"id\":9}"]);
    expect(events).toEqual([{ event: "message", data: '{"id":9}' }]);
  });

  it("handles a full ack→status→delta→message sequence", async () => {
    const events = await collect([
      "event: ack\ndata: {\"user_message_id\":1}\n\n",
      "event: status\ndata: {\"phase\":\"tool\",\"tool\":\"search_my_file\"}\n\n",
      "event: delta\ndata: {\"text\":\"Hello \"}\n\n",
      "event: delta\ndata: {\"text\":\"world\"}\n\n",
      "event: message\ndata: {\"id\":2,\"role\":\"assistant\",\"content\":\"Hello world\"}\n\n",
    ]);
    expect(events.map((e) => e.event)).toEqual(["ack", "status", "delta", "delta", "message"]);
  });
});
