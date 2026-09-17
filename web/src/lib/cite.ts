// Pure classifier for the `cite:` link scheme — the frozen wire format the chat
// agent emits inside markdown links (spec §E.2 / §G.5):
//   [38 CFR § 4.71a (as of 2026-06-09)](cite:cfr/4.71a)
//   [your dd214.pdf, 2019-03-02](cite:doc/123)
// The Markdown link renderer feeds every href through `parseCiteHref`. Anything
// that isn't a recognized cite: form returns null → render as plain text (never
// a dead <a>).

export type CiteTarget =
  | { kind: "cfr"; section: string; href: string }
  | { kind: "doc"; evidenceId: number; href: string };

/** 38 CFR sections live in Part 3 (3.x) or Part 4 (4.x). */
function cfrPart(section: string): "3" | "4" | null {
  if (section.startsWith("3.") || section === "3") return "3";
  if (section.startsWith("4.") || section === "4") return "4";
  return null;
}

/**
 * Classify a `cite:` href into a citation target, or null for non-cite / unknown
 * forms. Pure — safe to unit-test and to call in render.
 */
export function parseCiteHref(href: string | undefined | null): CiteTarget | null {
  if (!href) return null;
  if (!href.startsWith("cite:")) return null;
  const rest = href.slice("cite:".length);
  const slash = rest.indexOf("/");
  if (slash === -1) return null;
  const kind = rest.slice(0, slash);
  const id = rest.slice(slash + 1).trim();
  if (!id) return null;

  if (kind === "cfr") {
    const part = cfrPart(id);
    if (!part) return null;
    // eCFR canonical current-version URL for a 38 CFR section.
    const href = `https://www.ecfr.gov/current/title-38/chapter-I/part-${part}/section-${id}`;
    return { kind: "cfr", section: id, href };
  }
  if (kind === "doc") {
    const evidenceId = Number.parseInt(id, 10);
    if (!Number.isFinite(evidenceId) || evidenceId <= 0) return null;
    return { kind: "doc", evidenceId, href: `/documents?focus=${evidenceId}` };
  }
  return null;
}
