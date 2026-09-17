import type { NotificationDto, NotificationsEnvelope } from "@/lib/notifications";

// Notification fixtures in the LIVE GET /api/notifications shape (pinned Phase B
// contract): the digest row's metadata carries the full deterministic flip diff.
// Keep keys in lockstep with the backend writers — a drift here must fail CI.

/** The analysis_updated digest row written at a generation flip. */
export const analysisUpdatedDigest: NotificationDto = {
  id: 41,
  claimId: 99,
  eventType: "analysis_updated",
  title: "Your analysis was updated",
  body:
    "Your new evidence updated 2 conditions: PTSD rating estimate 50% -> 70%; " +
    "Right knee nexus evidence strengthened. 1 evidence gap closed.",
  severity: "success",
  conditionId: null,
  metadata: {
    runId: "run-7",
    ratingChanges: [{ conditionId: 93, name: "PTSD", from: 50, to: 70 }],
    legChanges: [{ conditionId: 97, name: "Right knee", leg: "nx", from: "weak", to: "strong" }],
    gapsClosed: 1,
    gapsOpened: 0,
    addedConditions: [{ conditionId: 103, name: "Tinnitus" }],
    retiredConditions: [],
    changedConditionIds: [93, 97, 103],
  },
  isRead: false,
  createdAt: "2026-07-02T14:05:00Z",
};

/** Granular per-condition rows from the same flip (already-read examples too). */
export const ratingChangedRow: NotificationDto = {
  id: 42,
  claimId: 99,
  eventType: "rating_changed",
  title: "PTSD rating estimate changed",
  body: "Your PTSD rating estimate went from 50% to 70%.",
  severity: "success",
  conditionId: 93,
  metadata: null,
  isRead: false,
  createdAt: "2026-07-02T14:05:00Z",
};

export const analysisCompleteRow: NotificationDto = {
  id: 7,
  claimId: 99,
  eventType: "analysis_complete",
  title: "Your first analysis is ready",
  body: "We found 6 conditions in your records.",
  severity: "success",
  conditionId: null,
  metadata: null,
  isRead: true,
  createdAt: "2026-06-28T09:30:00Z",
};

/** Envelope with one unread digest — drives the Home card + "Updated" pills. */
export const unreadDigestEnvelope: NotificationsEnvelope = {
  notifications: [analysisUpdatedDigest, ratingChangedRow, analysisCompleteRow],
  unreadCount: 2,
};

/** Envelope with history but nothing unread — Home shows the discreet link. */
export const allReadEnvelope: NotificationsEnvelope = {
  notifications: [
    { ...analysisUpdatedDigest, isRead: true },
    { ...ratingChangedRow, isRead: true },
    analysisCompleteRow,
  ],
  unreadCount: 0,
};
