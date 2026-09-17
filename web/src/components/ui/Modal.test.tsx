import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Modal } from "./Modal";

/* P1-25 — modals must be real dialogs: named, focus-trapped, Escape-closable,
   scroll-locking, and focus-restoring. */

function Harness({ ariaLabel, title }: { ariaLabel?: string; title?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button onClick={() => setOpen(true)}>opener</button>
      <Modal open={open} onClose={() => setOpen(false)} title={title} ariaLabel={ariaLabel}>
        <button>first</button>
        <button>last</button>
      </Modal>
    </>
  );
}

describe("Modal a11y (P1-25)", () => {
  it("is labelled by its title when one is given", async () => {
    render(<Harness title="Add to your claim" />);
    await userEvent.click(screen.getByRole("button", { name: "opener" }));
    const dialog = screen.getByRole("dialog", { name: "Add to your claim" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
  });

  it("is labelled by ariaLabel when title-less (DisclaimerModal case)", async () => {
    render(<Harness ariaLabel="About this guidance" />);
    await userEvent.click(screen.getByRole("button", { name: "opener" }));
    expect(screen.getByRole("dialog", { name: "About this guidance" })).toBeInTheDocument();
  });

  it("moves focus into the dialog on open and locks body scroll", async () => {
    render(<Harness title="T" />);
    await userEvent.click(screen.getByRole("button", { name: "opener" }));
    expect(screen.getByRole("dialog")).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
  });

  it("traps Tab: cycles last → first and Shift+Tab first → last", async () => {
    render(<Harness title="T" />);
    await userEvent.click(screen.getByRole("button", { name: "opener" }));
    const last = screen.getByRole("button", { name: "last" });
    last.focus();
    await userEvent.tab();
    // Wrapped around inside the dialog — never out to the page behind.
    expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
    screen.getByRole("button", { name: "Close" }).focus();
    await userEvent.tab({ shift: true });
    expect(last).toHaveFocus();
  });

  it("closes on Escape, restores focus to the opener, and unlocks scroll", async () => {
    render(<Harness title="T" />);
    const opener = screen.getByRole("button", { name: "opener" });
    await userEvent.click(opener);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(document.body.style.overflow).not.toBe("hidden");
  });

  it("keeps working for controlled parents that never change identity of onClose", async () => {
    // Regression guard: the trap effect depends on [open, onClose]; a stable
    // callback must not break close-on-escape after a re-render.
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="T">
        <button>x</button>
      </Modal>,
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });
});
