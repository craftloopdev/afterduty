import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { render, screen } from "@testing-library/react";
import LearnPage from "./page";
import HowVaRatesPage from "./how-va-rates/page";
import IntentToFilePage from "./intent-to-file/page";
import CpExamPage from "./cp-exam/page";
import VsoPage from "./vso/page";
import RecordsPage from "./records/page";
import { LEARN_TOPICS } from "@/components/education/topics";

// P1-18 education pack: the /learn hub + five static topic pages. All content
// is static veteran-plain language; the source-level test at the bottom pins
// the property that makes the native (Capacitor) export work — pure RSC with
// no data loads and no server-only imports.

describe("/learn hub", () => {
  it("renders a card link for every topic", () => {
    render(<LearnPage />);
    for (const t of LEARN_TOPICS) {
      const link = screen.getByRole("link", { name: new RegExp(t.title.slice(0, 12), "i") });
      expect(link).toHaveAttribute("href", `/learn/${t.slug}`);
      expect(screen.getByText(t.blurb)).toBeInTheDocument();
    }
    // The AppShell TopBar owns the "Learn" title — the hub renders no h1 of
    // its own (a page-level one would duplicate the TopBar heading).
    expect(screen.queryByRole("heading", { name: "Learn" })).not.toBeInTheDocument();
  });

  it("the free-for-everyone promise survives in the TopBar sub (its only user-facing home)", () => {
    // The hub's own header carried "Free for every veteran" before it was
    // removed as a TopBar duplicate; the promise moved into titleFor("/learn").
    const shell = readFileSync(
      resolve(__dirname, "../../../components/shell/AppShell.tsx"),
      "utf8",
    );
    expect(shell).toMatch(/free for every veteran/i);
  });
});

describe("topic pages", () => {
  it("How VA rates disabilities — triad in plain words, with Term popovers", () => {
    render(<HowVaRatesPage />);
    expect(
      screen.getByRole("heading", { name: /how va rates disabilities/i }),
    ).toBeInTheDocument();
    // The three legs, in order, as plain language.
    expect(screen.getByText(/three legs of every claim/i)).toBeInTheDocument();
    // Term triggers are real buttons (a11y) for the load-bearing jargon.
    expect(screen.getByRole("button", { name: "diagnosis" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "nexus" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "VASRD" })).toBeInTheDocument();
    // "pyramiding" now appears in both the percentage section and its own
    // dedicated explainer section — at least one Term button is present.
    expect(screen.getAllByRole("button", { name: "pyramiding" }).length).toBeGreaterThanOrEqual(1);
    // No promises: the honest hedge is on every article.
    expect(screen.getByText(/nothing here predicts or promises an outcome/i)).toBeInTheDocument();
  });

  it("How VA rates — has a #pyramiding explainer section for the pay-breakdown deep link", () => {
    const { container } = render(<HowVaRatesPage />);
    // The section the PayBreakdown "What is pyramiding?" link targets.
    const section = container.querySelector("#pyramiding");
    expect(section).not.toBeNull();
    expect(section).toHaveTextContent(/don.t stack|rated together|paid twice/i);
    expect(
      screen.getByRole("heading", { name: /pyramiding — why some conditions don.t add/i }),
    ).toBeInTheDocument();
  });

  it("Intent to File — teaches the back-pay stake and how to file free", () => {
    render(<IntentToFilePage />);
    expect(
      screen.getByRole("heading", { level: 1, name: /intent to file — lock in your date/i }),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/back pay/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/800-827-1000/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /va\.gov/i })).toHaveAttribute(
      "href",
      "https://www.va.gov/disability/how-to-file-claim/",
    );
    // Honesty: no approval promise.
    expect(screen.getByText(/doesn.t guarantee approval/i)).toBeInTheDocument();
  });

  it("C&P exam — what happens, what to bring, worst-days honesty", () => {
    render(<CpExamPage />);
    expect(
      screen.getByRole("heading", { level: 1, name: /getting ready for your c&p exam/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/what to bring/i)).toBeInTheDocument();
    expect(screen.getByText(/be honest about your worst days/i)).toBeInTheDocument();
    expect(screen.getByText(/don.t exaggerate\. ever\./i)).toBeInTheDocument();
  });

  it("VSOs — free help, accreditation link language", () => {
    render(<VsoPage />);
    expect(screen.getByRole("heading", { name: /free help: vsos/i })).toBeInTheDocument();
    expect(screen.getByText(/never charge to help you file a claim/i)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /accreditation search/i }),
    ).toHaveAttribute("href", "https://www.va.gov/ogc/apps/accreditation/index.asp");
    expect(
      screen.getByRole("link", { name: /accredited representative/i }),
    ).toHaveAttribute("href", "https://www.va.gov/get-help-from-accredited-representative/");
  });

  it("Gathering records — DD-214, STRs, portals, buddy statements with forms", () => {
    render(<RecordsPage />);
    expect(screen.getByRole("heading", { name: /gathering your records/i })).toBeInTheDocument();
    expect(screen.getByText(/your dd-214/i)).toBeInTheDocument();
    expect(screen.getByText(/service treatment records/i)).toBeInTheDocument();
    expect(screen.getAllByText(/SF-180/).length).toBeGreaterThan(0);
    expect(screen.getByText(/VA Form 21-10210/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /milconnect/i })).toBeInTheDocument();
  });
});

describe("native static-export safety (§A — pure RSC, no server-only imports)", () => {
  it("no learn page imports loaders, cookies, or client hooks", () => {
    const learnDir = resolve(process.cwd(), "src/app/(app)/learn");
    const pages: string[] = [];
    const walk = (dir: string) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (entry.isDirectory()) walk(join(dir, entry.name));
        else if (entry.name === "page.tsx") pages.push(join(dir, entry.name));
      }
    };
    walk(learnDir);
    expect(pages.length).toBeGreaterThanOrEqual(6); // hub + 5 topics

    for (const p of pages) {
      const src = readFileSync(p, "utf8");
      // No data loads / server-only APIs — these break `output:"export"`.
      expect(src, p).not.toMatch(/@\/lib\/api\/endpoints/);
      expect(src, p).not.toMatch(/next\/headers/);
      expect(src, p).not.toMatch(/"use server"/);
      // Static RSC: the page component itself is synchronous.
      expect(src, p).not.toMatch(/export default async function/);
      // No dynamic segments are used (belt-and-braces: folder routes only).
      expect(p).not.toMatch(/\[.+\]/);
    }
  });
});
