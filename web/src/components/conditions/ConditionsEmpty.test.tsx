import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ConditionsEmpty } from "./ConditionsEmpty";

// P1-16 free-tier coherence: a FREE user who already uploaded documents must
// not be told "Add your evidence" (a dead loop — their docs are stored but the
// analysis is Pro-gated). Tri-state: "error" is an honest unknown and must
// NEVER select the free copy.

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

describe("ConditionsEmpty (P1-16)", () => {
  it("free user WITH documents: stored-docs copy + upgrade link, no dead loop", () => {
    render(<ConditionsEmpty subState="free" documentsCount={3} />);
    expect(screen.getByText("Your documents are stored")).toBeInTheDocument();
    expect(screen.getByText(/AI analysis is a Pro feature/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Upgrade/ })).toHaveAttribute("href", "/upgrade");
    expect(screen.queryByText(/Add your evidence/)).not.toBeInTheDocument();
  });

  it("free user WITHOUT documents: the default add-evidence copy", () => {
    render(<ConditionsEmpty subState="free" documentsCount={0} />);
    expect(screen.getByText("No conditions yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Add evidence/ })).toHaveAttribute(
      "href",
      "/documents",
    );
    expect(screen.queryByText(/Pro feature/)).not.toBeInTheDocument();
  });

  it("subscription outage ('error') NEVER shows the free copy — even with documents", () => {
    render(<ConditionsEmpty subState="error" documentsCount={3} />);
    expect(screen.getByText("No conditions yet")).toBeInTheDocument();
    expect(screen.queryByText(/Pro feature/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Upgrade/ })).not.toBeInTheDocument();
  });

  it("pro user (analysis pending / none found upstream): default copy", () => {
    render(<ConditionsEmpty subState="pro" documentsCount={3} />);
    expect(screen.getByText("No conditions yet")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Upgrade/ })).not.toBeInTheDocument();
  });

  it("missing context (old callers): default copy, never the upsell", () => {
    render(<ConditionsEmpty />);
    expect(screen.getByText("No conditions yet")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Upgrade/ })).not.toBeInTheDocument();
  });
});
