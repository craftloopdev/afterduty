import { afterEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import type { CondVM } from "@/lib/models/vm";

// P0-9 native smoke test. The native export (NEXT_PUBLIC_NATIVE=1) has no
// `/conditions/[id]` route — scripts/native-export.mjs stashes it out of `out/`
// and only the `/conditions/detail?id=` twin exists in the WKWebView bundle.
// ConditionsList must therefore build hrefs through `condHref()` (platform.ts),
// never a literal `/conditions/${id}`. This renders the real component under
// both build flavors and asserts the hrefs that actually get tapped.

// `platform.ts` reads NEXT_PUBLIC_NATIVE at module-eval time, so each case
// stubs the env, resets the module registry, and imports the component fresh
// (same pattern as platform.test.ts).
vi.mock("next/link", () => ({
  default: ({
    href,
    children,
    ...rest
  }: React.PropsWithChildren<{ href: string } & Record<string, unknown>>) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

// ConditionsList calls useRouter().refresh() after an exclude/include toggle.
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

afterEach(() => {
  vi.resetModules();
  vi.unstubAllEnvs();
});

function cond(id: number, name: string): CondVM {
  const leg = { level: "strong" as const, items: ["Evidence on file"] };
  return {
    id,
    name,
    fullName: name,
    system: "Respiratory System",
    vasrdCode: "6602",
    rating: 60,
    confidence: 95,
    presumptive: null,
    triad: { dx: "strong", is: "strong", nx: "strong" },
    ready: true,
    rationale: null,
    ratingEvidenceNote: null,
    pyramidGroup: null,
    pyramidPrimary: false,
    pyramidReason: null,
    pyramidGroupRating: null,
    excludedFromClaim: false,
    legs: { dx: leg, is: leg, nx: leg },
    weakestLeg: null,
  };
}

async function renderList(conditions: CondVM[]) {
  const { ConditionsList } = await import("./ConditionsList");
  return render(<ConditionsList conditions={conditions} />);
}

describe("ConditionsList condition links (P0-9)", () => {
  it("native build: every condition link targets the /conditions/detail?id= twin", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "1");
    const { getAllByRole } = await renderList([cond(93, "PTSD"), cond(94, "Asthma")]);

    const hrefs = getAllByRole("link").map((a) => a.getAttribute("href"));
    // Desktop table row + mobile card per condition (CSS picks one at runtime).
    expect(hrefs).toHaveLength(4);
    expect(hrefs).toEqual(
      expect.arrayContaining(["/conditions/detail?id=93", "/conditions/detail?id=94"]),
    );
    for (const href of hrefs) {
      expect(href).toMatch(/^\/conditions\/detail\?id=\d+$/);
    }
  });

  it("web build: links keep the canonical dynamic route", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "");
    const { getAllByRole } = await renderList([cond(93, "PTSD")]);

    const hrefs = getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toHaveLength(2);
    for (const href of hrefs) {
      expect(href).toBe("/conditions/93");
    }
  });
});
