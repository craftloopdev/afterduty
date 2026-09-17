import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
import { AcceptShare } from "./AcceptShare";
import { acceptShareHref } from "@/lib/platform";

// A 401 on the share preview/accept bounces the viewer through /login and back to
// THIS invite. The return target must be a route that EXISTS in the running
// bundle: the native static export ships only the `/accept-share?token=` twin (the
// dynamic `/accept-share/[token]` is stashed out by native-export.mjs), so the
// redirect must be built from `acceptShareHref`, never a hardcoded canonical path.
// Otherwise the post-login `afterAuth()` -> safeNext(next) lands on a 404 in the
// WKWebView and the share-accept universal-link flow dead-ends (dim 6/7, §A.3a).

const previewShare = vi.fn();
const acceptShare = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  previewShare: (...a: unknown[]) => previewShare(...a),
  acceptShare: (...a: unknown[]) => acceptShare(...a),
}));

const replace = vi.fn();
const origLocation = window.location;

beforeEach(() => {
  previewShare.mockReset();
  acceptShare.mockReset();
  replace.mockReset();
  // jsdom's window.location is non-configurable to assign; swap for a stub.
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { ...origLocation, replace },
  });
});

afterEach(() => {
  Object.defineProperty(window, "location", {
    configurable: true,
    value: origLocation,
  });
});

describe("AcceptShare 401 → login bounce", () => {
  it("redirects to /login with next = acceptShareHref(token), not a hardcoded path", async () => {
    previewShare.mockResolvedValue(new Response(null, { status: 401 }));

    render(<AcceptShare token="TOK123" />);

    await waitFor(() => expect(replace).toHaveBeenCalledTimes(1));

    const expected = `/login?next=${encodeURIComponent(acceptShareHref("TOK123"))}`;
    expect(replace).toHaveBeenCalledWith(expected);
    // On the web test build acceptShareHref is the canonical path; assert the
    // helper output is actually what was used (guards against a literal regress).
    expect(replace.mock.calls[0][0]).toContain(
      encodeURIComponent(acceptShareHref("TOK123")),
    );
  });
});
