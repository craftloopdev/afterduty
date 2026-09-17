import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import {
  armAnalysisPulse,
  deriveStages,
  disarmAnalysisPulse,
  fetchJobs,
  isAnalysisPulseArmed,
  jobsActive,
  subscribeAnalysisPulse,
  type JobsSnapshot,
} from "./jobs";

describe("jobsActive", () => {
  it("is true only when the snapshot reports in-flight work", () => {
    expect(jobsActive(null)).toBe(false);
    expect(jobsActive({})).toBe(false);
    expect(jobsActive({ active_count: 0 })).toBe(false);
    expect(jobsActive({ active_count: 2 })).toBe(true);
  });
});

describe("deriveStages", () => {
  it("renders all-waiting for an empty snapshot (no fake progress)", () => {
    expect(deriveStages(null).map((s) => s.state)).toEqual(["waiting", "waiting", "waiting"]);
  });

  it("marks reading active while extraction has pending/processing docs", () => {
    const jobs: JobsSnapshot = { extraction: { pending: 1, processing: 1, processed: 0, errored: 0 } };
    const [read, conds, gaps] = deriveStages(jobs);
    expect(read.state).toBe("active");
    expect(conds.state).toBe("waiting");
    expect(gaps.state).toBe("waiting");
  });

  it("walks done → active as the pipeline advances", () => {
    const jobs: JobsSnapshot = {
      extraction: { pending: 0, processing: 0, processed: 3, errored: 0 },
      synthesis: { state: "running" },
      gap_analysis: { state: "idle" },
    };
    const [read, conds, gaps] = deriveStages(jobs);
    expect(read.state).toBe("done");
    expect(conds.state).toBe("active");
    expect(gaps.state).toBe("waiting");
  });

  it("marks conditions done once they exist and gap analysis done after a run", () => {
    const jobs: JobsSnapshot = {
      summary: { conditions: 4 },
      extraction: { pending: 0, processing: 0, processed: 3, errored: 0 },
      synthesis: { state: "idle" },
      gap_analysis: { state: "idle", last_run_at: "2026-07-01T12:00:00Z" },
    };
    expect(deriveStages(jobs).map((s) => s.state)).toEqual(["done", "done", "done"]);
  });
});

describe("pulse arming (sessionStorage + event)", () => {
  beforeEach(() => {
    // Clears both the storage flag and the in-memory fallback.
    disarmAnalysisPulse();
  });

  it("arms, reads back, and disarms", () => {
    expect(isAnalysisPulseArmed()).toBe(false);
    armAnalysisPulse();
    expect(isAnalysisPulseArmed()).toBe(true);
    disarmAnalysisPulse();
    expect(isAnalysisPulseArmed()).toBe(false);
  });

  it("expires a stale arm flag after the TTL", () => {
    armAnalysisPulse();
    const later = Date.now() + 31 * 60 * 1000;
    expect(isAnalysisPulseArmed(later)).toBe(false);
  });

  it("notifies subscribers on arm AND disarm (useSyncExternalStore contract)", () => {
    const woke = vi.fn();
    const unsubscribe = subscribeAnalysisPulse(woke);
    armAnalysisPulse();
    expect(woke).toHaveBeenCalledTimes(1);
    disarmAnalysisPulse();
    expect(woke).toHaveBeenCalledTimes(2);
    unsubscribe();
    armAnalysisPulse();
    expect(woke).toHaveBeenCalledTimes(2);
  });

  it("arms via the in-memory fallback even when sessionStorage is unavailable", () => {
    const setItem = vi
      .spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {
        throw new Error("private mode");
      });
    try {
      armAnalysisPulse();
      expect(isAnalysisPulseArmed()).toBe(true);
    } finally {
      setItem.mockRestore();
      disarmAnalysisPulse();
    }
  });
});

describe("fetchJobs (web transport)", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GETs the BFF route and returns the parsed snapshot", async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ active_count: 1 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const jobs = await fetchJobs();
    expect(fetchMock).toHaveBeenCalledWith("/api/claim/jobs", { cache: "no-store" });
    expect(jobs?.active_count).toBe(1);
  });

  it("returns null on HTTP errors and on network failures (never throws)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("nope", { status: 502 })));
    expect(await fetchJobs()).toBeNull();
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new Error("offline"))));
    expect(await fetchJobs()).toBeNull();
  });
});
