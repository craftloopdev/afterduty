import { describe, it, expect, beforeEach, vi } from "vitest";

// The self-mount fallback lazy-loads StepUpModal, whose host watches the auth
// account for channel choice — stub the driver so no Firebase config is needed.
vi.mock("@/lib/auth", () => ({
  authDriver: {
    watchAccount: (cb: (d: unknown) => void) => {
      cb({ email: "vet@example.com", phone: null });
      return () => {};
    },
    startPhone: vi.fn(),
    getToken: vi.fn(),
    resetPhoneVerifier: vi.fn(),
  },
}));

import {
  withStepUp,
  registerStepUpOpener,
  cacheStepUpToken,
  getCachedStepUpToken,
  clearStepUpToken,
  STEP_UP_HEADER,
  StepUpCancelledError,
  __resetStepUpForTests,
  type StepUpResult,
} from "./step-up";

// The client step-up seam (auth-program-plan P1.2). Mirrors the 401-retry seam:
// a `403 {code:step_up_required}` runs the ceremony, then retries ONCE with the
// X-Step-Up header. A still-valid cached token skips the prompt entirely.

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}
const STEP_UP_403 = () => json(403, { code: "step_up_required", acceptedFactors: ["otp"] });
const OK = () => json(200, { ok: true });

/** A ceremony opener that resolves with a fixed token, counting how many times
 *  it was invoked (to assert prompt-suppression via the cache). */
function stubCeremony(token: string, expiresInSec = 300) {
  const calls = { n: 0 };
  const unregister = registerStepUpOpener(async (): Promise<StepUpResult> => {
    calls.n++;
    return { token, expiresInSec };
  });
  return { calls, unregister };
}

beforeEach(() => {
  __resetStepUpForTests();
  clearStepUpToken();
});

describe("step-up token cache", () => {
  it("returns a cached token within its TTL and drops it after expiry", () => {
    cacheStepUpToken("tok", 300);
    expect(getCachedStepUpToken()).toBe("tok");
    // A sub-skew TTL (1s) is already stale (the 10s safety skew pushes expiresAt
    // into the past) — the seam re-prompts rather than send a near-dead token.
    cacheStepUpToken("tok2", 1);
    expect(getCachedStepUpToken()).toBeNull();
  });

  it("clearStepUpToken removes the token (sign-out hygiene)", () => {
    cacheStepUpToken("tok", 300);
    clearStepUpToken();
    expect(getCachedStepUpToken()).toBeNull();
  });
});

describe("withStepUp — 403 step_up_required interception", () => {
  it("runs the ceremony and retries ONCE with the X-Step-Up header", async () => {
    const { calls, unregister } = stubCeremony("STEPTOKEN");
    const send = vi
      .fn<(h: Record<string, string>) => Promise<Response>>()
      .mockResolvedValueOnce(STEP_UP_403())
      .mockResolvedValueOnce(OK());

    const res = await withStepUp(send);

    expect(res.status).toBe(200);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[0][0]).toEqual({}); // first attempt, no header
    expect(send.mock.calls[1][0]).toEqual({ [STEP_UP_HEADER]: "STEPTOKEN" });
    expect(calls.n).toBe(1);
    unregister();
  });

  it("caches the token so a back-to-back guarded call does NOT re-prompt", async () => {
    const { calls, unregister } = stubCeremony("CACHED");

    const send1 = vi.fn().mockResolvedValueOnce(STEP_UP_403()).mockResolvedValueOnce(OK());
    await withStepUp(send1 as never);

    const send2 = vi.fn().mockResolvedValueOnce(STEP_UP_403()).mockResolvedValueOnce(OK());
    const res2 = await withStepUp(send2 as never);

    expect(res2.status).toBe(200);
    expect(calls.n).toBe(1); // ceremony ran once total, reused for the 2nd call
    expect((send2 as ReturnType<typeof vi.fn>).mock.calls[1][0]).toEqual({
      [STEP_UP_HEADER]: "CACHED",
    });
    unregister();
  });

  it("passes a plain (non-step-up) 403 straight through untouched", async () => {
    const { calls, unregister } = stubCeremony("UNUSED");
    const send = vi.fn().mockResolvedValueOnce(json(403, { error: "forbidden" }));
    const res = await withStepUp(send as never);
    expect(res.status).toBe(403);
    expect(send).toHaveBeenCalledTimes(1); // no retry
    expect(calls.n).toBe(0); // ceremony never ran
    unregister();
  });

  it("does not consume the caller's response body when peeking a plain 403", async () => {
    const { unregister } = stubCeremony("UNUSED");
    const send = vi.fn().mockResolvedValueOnce(json(403, { error: "forbidden" }));
    const res = await withStepUp(send as never);
    // The caller can still read the body — withStepUp cloned to peek.
    await expect(res.json()).resolves.toEqual({ error: "forbidden" });
    unregister();
  });

  it("propagates StepUpCancelledError when the user cancels the ceremony", async () => {
    const unregister = registerStepUpOpener(async () => {
      throw new StepUpCancelledError();
    });
    const send = vi.fn().mockResolvedValueOnce(STEP_UP_403());
    await expect(withStepUp(send as never)).rejects.toBeInstanceOf(StepUpCancelledError);
    expect(send).toHaveBeenCalledTimes(1); // never retried
    unregister();
  });

  it("drops the cached token when the retry is STILL step_up (single retry, no loop)", async () => {
    const { unregister } = stubCeremony("STALE");
    const send = vi
      .fn()
      .mockResolvedValueOnce(STEP_UP_403())
      .mockResolvedValueOnce(STEP_UP_403()); // token rejected
    const res = await withStepUp(send as never);
    expect(res.status).toBe(403); // handed back after ONE retry
    expect(send).toHaveBeenCalledTimes(2);
    expect(getCachedStepUpToken()).toBeNull(); // cache dropped for the next attempt
    unregister();
  });

  it("coalesces concurrent step-ups into a SINGLE ceremony", async () => {
    let resolveCeremony!: (r: StepUpResult) => void;
    const calls = { n: 0 };
    const unregister = registerStepUpOpener(
      () =>
        new Promise<StepUpResult>((resolve) => {
          calls.n++;
          resolveCeremony = resolve;
        }),
    );

    const mk = () => vi.fn().mockResolvedValueOnce(STEP_UP_403()).mockResolvedValueOnce(OK());
    const a = withStepUp(mk() as never);
    const b = withStepUp(mk() as never);
    // Both must hit their 403, parse it, and enter the (shared) ceremony.
    await vi.waitFor(() => expect(calls.n).toBe(1)); // ONE prompt for both

    resolveCeremony({ token: "SHARED", expiresInSec: 300 });
    const [ra, rb] = await Promise.all([a, b]);
    expect(ra.status).toBe(200);
    expect(rb.status).toBe(200);
    unregister();
  });
});

describe("self-mount fallback — no page ever mounted a StepUpHost", () => {
  it("lazy-loads the modal module and opens the ceremony instead of throwing", async () => {
    // The 2026-08-02 audit found no production module imports StepUpModal, so
    // the module-scope setStepUpSelfMount never ran and a guarded 403 THREW.
    // runStepUp must dynamic-import the modal module and self-mount.
    const send = vi
      .fn<(extra?: Record<string, string>) => Promise<Response>>()
      .mockResolvedValueOnce(STEP_UP_403())
      .mockResolvedValue(OK());

    const result = withStepUp(send);

    // The ceremony UI must appear (dialog title from StepUpModal via Modal).
    await vi.waitFor(() => {
      expect(document.body.textContent).toContain("Confirm it's you");
    });

    // Cancel it — the guarded call surfaces the typed cancel, not a raw error.
    const cancelBtn = Array.from(document.querySelectorAll("button")).find(
      (b) => /cancel/i.test(b.textContent ?? ""),
    );
    expect(cancelBtn).toBeTruthy();
    cancelBtn!.click();

    await expect(result).rejects.toBeInstanceOf(StepUpCancelledError);
    expect(send).toHaveBeenCalledTimes(1); // no retry after cancel
  });
});
