import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TriadDots } from "./TriadDots";

/* P1-26 — triad status must never be conveyed by color alone. */

describe("TriadDots a11y (P1-26)", () => {
  const triad = { dx: "strong", is: "partial", nx: "missing" } as const;

  it("names every leg + level for screen readers (visually-hidden prefixes)", () => {
    render(<TriadDots triad={triad} />);
    expect(screen.getByText("Diagnosis: Strong")).toHaveClass("visually-hidden");
    expect(screen.getByText("In-Service: Partial")).toHaveClass("visually-hidden");
    expect(screen.getByText("Nexus: Missing")).toHaveClass("visually-hidden");
    // The visible abbreviations are redundant for AT.
    expect(screen.getByText("Dx")).toHaveAttribute("aria-hidden", "true");
  });

  it("renders a distinct status GLYPH per level (shape, not just color)", () => {
    const { container } = render(<TriadDots triad={triad} />);
    // strong→check, partial→alert, missing→dash: three different path sets.
    const svgs = Array.from(container.querySelectorAll("svg"));
    expect(svgs).toHaveLength(3);
    const shapes = svgs.map((s) => s.innerHTML);
    expect(new Set(shapes).size).toBe(3);
  });

  it("keeps the mouse-hover title on each pill", () => {
    render(<TriadDots triad={triad} />);
    expect(screen.getByTitle("Diagnosis: Strong")).toBeInTheDocument();
    expect(screen.getByTitle("Nexus: Missing")).toBeInTheDocument();
  });
});
