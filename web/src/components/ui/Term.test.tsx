import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Term, TERMS, type TermKey } from "./Term";
import { triadLeg } from "@/lib/theme/tokens";

// P1-18 <Term> popover: a real button (keyboard/AT reachable) that toggles an
// inline definition. Static dictionary — the triad-leg entries must reuse the
// canonical leg descriptions from lib/theme/tokens so they can never drift.

describe("Term — inline jargon popover", () => {
  it("renders a button trigger, collapsed by default", () => {
    render(
      <p>
        Ask for a <Term k="nexus">nexus letter</Term> from your doctor.
      </p>,
    );
    const btn = screen.getByRole("button", { name: "nexus letter" });
    expect(btn).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("click opens the definition; aria-expanded + aria-describedby wire up; click again closes", async () => {
    const user = userEvent.setup();
    render(<Term k="itf">Intent to File</Term>);
    const btn = screen.getByRole("button", { name: "Intent to File" });

    await user.click(btn);
    expect(btn).toHaveAttribute("aria-expanded", "true");
    const note = screen.getByRole("note");
    expect(note).toHaveTextContent(/locks in your date/i);
    expect(note).toHaveTextContent(/back pay/i);
    // The trigger points AT the note for assistive tech.
    expect(btn).toHaveAttribute("aria-describedby", note.getAttribute("id"));

    await user.click(btn);
    expect(btn).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("Escape closes the popover", async () => {
    const user = userEvent.setup();
    render(<Term k="vasrd">VASRD</Term>);
    const btn = screen.getByRole("button", { name: "VASRD" });
    await user.click(btn);
    expect(screen.getByRole("note")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("covers the full required dictionary with non-empty plain-language entries", () => {
    const required: TermKey[] = [
      "nexus",
      "diagnosis",
      "in-service",
      "severity",
      "vasrd",
      "dc-code",
      "presumptive",
      "pyramiding",
      "cp-exam",
      "itf",
      "effective-date",
    ];
    for (const k of required) {
      expect(TERMS[k].title.length).toBeGreaterThan(0);
      expect(TERMS[k].def.length).toBeGreaterThan(20);
      // No outcome promises anywhere in the dictionary.
      expect(TERMS[k].def).not.toMatch(/guarantee|will win|will be granted/i);
    }
  });

  it("triad-leg entries reuse the canonical leg descriptions (no drift)", () => {
    expect(TERMS.diagnosis.def).toContain(triadLeg("dx").desc);
    expect(TERMS["in-service"].def).toContain(triadLeg("is").desc);
    expect(TERMS.nexus.def).toContain(triadLeg("nx").desc);
  });
});
