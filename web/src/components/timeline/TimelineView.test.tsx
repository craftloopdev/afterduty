import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TimelineView } from "./TimelineView";
import { groupNotificationsByDay } from "@/lib/notifications";
import type { NotificationDto } from "@/lib/notifications";

// Build ISO stamps from LOCAL dates so day-grouping expectations hold in any
// CI timezone.
const at = (y: number, m: number, d: number, h: number) => new Date(y, m - 1, d, h).toISOString();

const JOURNAL: NotificationDto[] = [
  {
    id: 41,
    eventType: "analysis_updated",
    title: "Your analysis was updated",
    body: "Your new evidence updated 2 conditions.",
    severity: "success",
    isRead: false,
    createdAt: at(2026, 7, 2, 14),
  },
  {
    id: 42,
    eventType: "rating_changed",
    title: "PTSD rating estimate changed",
    body: "Your PTSD rating estimate went from 50% to 70%.",
    severity: "success",
    conditionId: 93,
    isRead: false,
    createdAt: at(2026, 7, 2, 14),
  },
  {
    id: 7,
    eventType: "analysis_complete",
    title: "Your first analysis is ready",
    body: "We found 6 conditions in your records.",
    severity: "success",
    isRead: true,
    createdAt: at(2026, 6, 28, 9),
  },
];

describe("TimelineView", () => {
  it("groups the journal by day with a header per day", () => {
    render(<TimelineView days={groupNotificationsByDay(JOURNAL)} />);

    const headers = screen.getAllByRole("heading", { level: 2 });
    expect(headers.map((h) => h.textContent)).toEqual(["July 2, 2026", "June 28, 2026"]);

    // Read AND unread rows both render — the journal is complete.
    expect(screen.getByText("Your analysis was updated")).toBeInTheDocument();
    expect(screen.getByText("Your first analysis is ready")).toBeInTheDocument();
    // Unread rows carry the accessible unread marker; read rows don't.
    expect(screen.getAllByRole("img", { name: "Unread" })).toHaveLength(2);
  });

  it("links condition-scoped entries via condHref", () => {
    render(<TimelineView days={groupNotificationsByDay(JOURNAL)} />);
    const links = screen.getAllByRole("link", { name: /view condition/i });
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "/conditions/93");
  });

  it("shows a calm empty state when there is no journal yet", () => {
    render(<TimelineView days={[]} />);
    expect(screen.getByText(/no updates yet/i)).toBeInTheDocument();
  });

  it("renders the full what-changed detail from metadata, replacing the prose body", () => {
    const rich: NotificationDto[] = [
      {
        id: 50,
        eventType: "analysis_updated",
        title: "Your analysis was updated",
        body: "Your new evidence updated 2 conditions.",
        severity: "success",
        isRead: false,
        createdAt: at(2026, 8, 2, 10),
        metadata: {
          runId: "r1",
          ratingChanges: [{ conditionId: 93, name: "PTSD", from: 50, to: 70 }],
          // 4 additions — above the Home card's cap of 3. The timeline is the
          // uncapped surface: every line must render, no "and N more".
          addedConditions: [
            { conditionId: 1, name: "Tinnitus" },
            { conditionId: 2, name: "Sleep apnea" },
            { conditionId: 3, name: "Right knee strain" },
            { conditionId: 4, name: "Migraines" },
          ],
          gapsClosed: 1,
        },
      },
    ];
    render(<TimelineView days={groupNotificationsByDay(rich)} />);

    // Structured lines replace the run-on body.
    expect(screen.queryByText("Your new evidence updated 2 conditions.")).not.toBeInTheDocument();
    expect(screen.getByText(/PTSD: rating estimate 50% → 70%/)).toBeInTheDocument();
    expect(screen.getByText("New conditions")).toBeInTheDocument();
    for (const name of ["Tinnitus", "Sleep apnea", "Right knee strain", "Migraines"]) {
      expect(screen.getByRole("link", { name: new RegExp(name) })).toBeInTheDocument();
    }
    expect(screen.queryByText(/and \d+ more/)).not.toBeInTheDocument();
    expect(screen.getByText(/1 evidence gap closed/)).toBeInTheDocument();
  });

  it("lists the conditions found on a first-ever analysis", () => {
    const first: NotificationDto[] = [
      {
        id: 60,
        eventType: "analysis_complete",
        title: "Analysis complete",
        body: "We found 2 conditions in your records.",
        severity: "success",
        isRead: false,
        createdAt: at(2026, 8, 2, 9),
        metadata: {
          runId: "r0",
          conditionCount: 2,
          addedConditions: [
            { conditionId: 5, name: "PTSD" },
            { conditionId: 6, name: "Tinnitus" },
          ],
        },
      },
    ];
    render(<TimelineView days={groupNotificationsByDay(first)} />);

    // First run: everything is new, so the heading says "found", not "new".
    expect(screen.getByText("Conditions found")).toBeInTheDocument();
    expect(screen.queryByText("New conditions")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /PTSD/ })).toHaveAttribute("href", "/conditions/5");
    expect(screen.getByRole("link", { name: /Tinnitus/ })).toBeInTheDocument();
    // Rows without metadata (older backend rows) keep their body verbatim —
    // and analysis_complete rows WITH metadata drop the count sentence.
    expect(screen.queryByText("We found 2 conditions in your records.")).not.toBeInTheDocument();
  });

  it("falls back to the body for pre-enrichment rows ({runId, conditionCount} metadata)", () => {
    const legacy: NotificationDto[] = [
      {
        id: 61,
        eventType: "analysis_complete",
        title: "Analysis complete",
        body: "We found 8 conditions in your records.",
        severity: "success",
        isRead: true,
        createdAt: at(2026, 8, 1, 9),
        // The exact shape the backend wrote before addedConditions existed:
        // non-null metadata, but nothing digestGroups can build lines from.
        metadata: { runId: "r-legacy", conditionCount: 8 },
      },
    ];
    render(<TimelineView days={groupNotificationsByDay(legacy)} />);
    expect(screen.getByText("We found 8 conditions in your records.")).toBeInTheDocument();
    expect(screen.queryByText("Conditions found")).not.toBeInTheDocument();
  });
});
