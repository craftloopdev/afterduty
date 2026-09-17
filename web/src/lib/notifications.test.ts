import { describe, it, expect } from "vitest";
import {
  digestLines,
  groupNotificationsByDay,
  notificationIcon,
  toAnalysisUpdates,
  toNotification,
  toNotificationsEnvelope,
  type NotificationDto,
} from "./notifications";
import {
  allReadEnvelope,
  analysisUpdatedDigest,
  unreadDigestEnvelope,
} from "./fixtures/notifications";

describe("toNotificationsEnvelope", () => {
  it("normalizes the live shape and drops rows without a numeric id", () => {
    const env = toNotificationsEnvelope({
      notifications: [analysisUpdatedDigest, { title: "no id" }, null, 7],
      unreadCount: 1,
    });
    expect(env.notifications).toHaveLength(1);
    expect(env.unreadCount).toBe(1);
  });

  it("degrades garbage/absent bodies to empty, never throws", () => {
    expect(toNotificationsEnvelope(null)).toEqual({ notifications: [], unreadCount: 0 });
    expect(toNotificationsEnvelope("oops")).toEqual({ notifications: [], unreadCount: 0 });
    expect(toNotificationsEnvelope({ notifications: "x", unreadCount: "y" })).toEqual({
      notifications: [],
      unreadCount: 0,
    });
  });
});

describe("toAnalysisUpdates", () => {
  it("picks the latest unread digest and its changed condition ids", () => {
    const vm = toAnalysisUpdates(unreadDigestEnvelope);
    expect(vm.digest?.id).toBe(41);
    expect(vm.digest?.eventType).toBe("analysis_updated");
    expect(vm.digestIds).toEqual([41]); // rating_changed rows are not digests
    expect(vm.changedConditionIds).toEqual([93, 97, 103]);
    expect(vm.hasAny).toBe(true);
  });

  it("has no digest (but hasAny) when everything is read", () => {
    const vm = toAnalysisUpdates(allReadEnvelope);
    expect(vm.digest).toBeNull();
    expect(vm.digestIds).toEqual([]);
    expect(vm.changedConditionIds).toEqual([]);
    expect(vm.hasAny).toBe(true);
  });

  it("treats an unread analysis_complete as a digest too (first analysis)", () => {
    const vm = toAnalysisUpdates({
      notifications: [
        {
          id: 7,
          eventType: "analysis_complete",
          title: "Your first analysis is ready",
          body: "We found 6 conditions in your records.",
          isRead: false,
          createdAt: "2026-07-02T14:00:00Z",
        },
      ],
      unreadCount: 1,
    });
    expect(vm.digest?.id).toBe(7);
    // No analysis_updated row → no pills.
    expect(vm.changedConditionIds).toEqual([]);
  });

  it("is empty on an empty journal", () => {
    const vm = toAnalysisUpdates({ notifications: [], unreadCount: 0 });
    expect(vm.digest).toBeNull();
    expect(vm.hasAny).toBe(false);
  });
});

describe("digestLines — deterministic templates over the flip diff", () => {
  it("renders rating, leg, added-condition, and gap-count lines", () => {
    const lines = digestLines(toNotification(analysisUpdatedDigest));
    expect(lines.map((l) => l.text)).toEqual([
      "PTSD: rating estimate 50% → 70%",
      "Right knee: Nexus evidence weak → strong",
      "Tinnitus: new condition identified",
      "1 evidence gap closed",
    ]);
    // Condition-scoped lines link; the summary line doesn't.
    expect(lines[0].conditionId).toBe(93);
    expect(lines[3].conditionId).toBeNull();
  });

  it("states a null rating honestly ('not yet ratable'), never a fake 0%", () => {
    const n = toNotification({
      ...analysisUpdatedDigest,
      metadata: { ratingChanges: [{ conditionId: 1, name: "GERD", from: null, to: 30 }] },
    });
    expect(digestLines(n)[0].text).toBe("GERD: rating estimate not yet ratable → 30%");
  });

  it("renders no lines when metadata is absent", () => {
    expect(digestLines(toNotification({ id: 1, eventType: "analysis_updated" }))).toEqual([]);
  });
});

describe("groupNotificationsByDay", () => {
  // Build ISO stamps from LOCAL dates so the expected grouping is
  // deterministic in any CI timezone.
  const at = (y: number, m: number, d: number, h: number) =>
    new Date(y, m - 1, d, h).toISOString();

  const row = (id: number, createdAt: string): NotificationDto => ({
    id,
    eventType: "rating_changed",
    title: `n${id}`,
    createdAt,
  });

  it("groups by local day, preserving createdAt-DESC order", () => {
    const days = groupNotificationsByDay([
      row(3, at(2026, 7, 2, 15)),
      row(2, at(2026, 7, 2, 9)),
      row(1, at(2026, 6, 28, 12)),
    ]);
    expect(days).toHaveLength(2);
    expect(days[0].label).toBe("July 2, 2026");
    expect(days[0].items.map((n) => n.id)).toEqual([3, 2]);
    expect(days[1].label).toBe("June 28, 2026");
  });

  it("buckets unparseable timestamps under 'Earlier' instead of crashing", () => {
    const days = groupNotificationsByDay([row(1, "not-a-date")]);
    expect(days).toHaveLength(1);
    expect(days[0].label).toBe("Earlier");
  });
});

describe("notificationIcon", () => {
  it("maps each journal event type, with an info fallback", () => {
    expect(notificationIcon("analysis_updated")).toBe("sparkle");
    expect(notificationIcon("analysis_complete")).toBe("checkCircle");
    expect(notificationIcon("condition_added")).toBe("conditions");
    expect(notificationIcon("rating_changed")).toBe("money");
    expect(notificationIcon("someday_new_type")).toBe("info");
  });
});
