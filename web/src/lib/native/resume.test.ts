import { afterEach, describe, expect, it, vi } from "vitest";

// P1-22 native freshness: the resume helper must register a Capacitor App
// `resume` listener ONLY on the native build, and must never touch (or even
// import) @capacitor/app on web. `platform.ts` reads NEXT_PUBLIC_NATIVE at
// module-eval time, so each case stubs the env, resets the module registry,
// and imports fresh (same pattern as platform.test.ts).

const addListener = vi.fn();
const removeHandle = vi.fn();
let pluginImported = false;

vi.mock("@capacitor/app", () => {
  pluginImported = true;
  return {
    App: {
      addListener: (...args: unknown[]) => addListener(...args),
    },
  };
});

afterEach(() => {
  vi.resetModules();
  vi.unstubAllEnvs();
  addListener.mockReset();
  removeHandle.mockReset();
  pluginImported = false;
});

const flush = () => new Promise((r) => setTimeout(r, 0));

async function loadResume() {
  return import("./resume");
}

describe("installResumeListener", () => {
  it("web build: no-op — @capacitor/app is never imported", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "");
    const { installResumeListener } = await loadResume();
    const teardown = installResumeListener(() => {});
    await flush();
    expect(pluginImported).toBe(false);
    expect(addListener).not.toHaveBeenCalled();
    teardown(); // must be safe to call
  });

  it("native build: registers a 'resume' listener that fires the callback", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "1");
    addListener.mockResolvedValue({ remove: removeHandle });
    const onResume = vi.fn();
    const { installResumeListener } = await loadResume();
    installResumeListener(onResume);
    await flush();

    expect(addListener).toHaveBeenCalledTimes(1);
    const [event, cb] = addListener.mock.calls[0] as [string, () => void];
    expect(event).toBe("resume");
    cb();
    expect(onResume).toHaveBeenCalledTimes(1);
  });

  it("native build: teardown removes the listener", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "1");
    addListener.mockResolvedValue({ remove: removeHandle });
    const { installResumeListener } = await loadResume();
    const teardown = installResumeListener(() => {});
    await flush();
    teardown();
    expect(removeHandle).toHaveBeenCalledTimes(1);
  });

  it("native build: teardown BEFORE the plugin loads never registers (no leak)", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "1");
    addListener.mockResolvedValue({ remove: removeHandle });
    const { installResumeListener } = await loadResume();
    const teardown = installResumeListener(() => {});
    teardown(); // synchronously, before the dynamic import resolves
    await flush();
    expect(addListener).not.toHaveBeenCalled();
  });

  it("native build: teardown while addListener is in flight still removes the handle", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "1");
    let resolveHandle!: (h: { remove: () => void }) => void;
    addListener.mockReturnValue(
      new Promise<{ remove: () => void }>((r) => {
        resolveHandle = r;
      }),
    );
    const { installResumeListener } = await loadResume();
    const teardown = installResumeListener(() => {});
    await flush(); // dynamic import resolved; addListener called, promise pending
    expect(addListener).toHaveBeenCalledTimes(1);
    teardown();
    resolveHandle({ remove: removeHandle });
    await flush();
    expect(removeHandle).toHaveBeenCalledTimes(1);
  });
});
