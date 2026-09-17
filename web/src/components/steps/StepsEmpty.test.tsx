import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { StepsEmpty } from "./StepsEmpty";

// P1-16 free-tier coherence for the Steps screen — mirrors ConditionsEmpty:
// free-with-docs gets the honest Pro boundary + upgrade path; "error"
// (tri-state honest unknown) and free-with-no-docs fall through to the
// neutral default.

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

describe("StepsEmpty (P1-16)", () => {
  it("free user WITH documents: stored-docs copy + upgrade link", () => {
    render(<StepsEmpty subState="free" documentsCount={2} />);
    expect(screen.getByText("Your documents are stored")).toBeInTheDocument();
    expect(screen.getByText(/AI analysis is a Pro feature/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Upgrade/ })).toHaveAttribute("href", "/upgrade");
    expect(screen.queryByText(/Add your evidence/)).not.toBeInTheDocument();
  });

  it("free user WITHOUT documents: the default add-evidence copy", () => {
    render(<StepsEmpty subState="free" documentsCount={0} />);
    expect(screen.getByText("No steps yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Add evidence/ })).toHaveAttribute(
      "href",
      "/documents",
    );
  });

  it("subscription outage ('error') NEVER shows the free copy", () => {
    render(<StepsEmpty subState="error" documentsCount={2} />);
    expect(screen.getByText("No steps yet")).toBeInTheDocument();
    expect(screen.queryByText(/Pro feature/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Upgrade/ })).not.toBeInTheDocument();
  });
});
