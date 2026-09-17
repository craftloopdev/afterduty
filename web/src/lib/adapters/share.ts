import type { ShareDto } from "@/lib/models/api";
import type { ShareStatus, ShareVM } from "@/lib/models/vm";

const SERVER_STATUSES: ReadonlySet<string> = new Set([
  "pending",
  "accepted",
  "expired",
  "revoked",
]);

export function shareStatus(s: ShareDto, now: number = Date.now()): ShareStatus {
  // Prefer the backend-computed lifecycle status (P2-2) — it owns the
  // precedence rules. The date-derived fallback covers older backends only.
  if (s.status && SERVER_STATUSES.has(s.status)) return s.status as ShareStatus;
  if (s.revokedAt) return "revoked";
  if (s.acceptedAt) return "accepted";
  if (s.invitationExpiresAt && new Date(s.invitationExpiresAt).getTime() < now) return "expired";
  return "pending";
}

export function toShare(s: ShareDto, now: number = Date.now()): ShareVM {
  return {
    id: s.id,
    email: s.viewerEmail ?? "",
    canViewAnalysis: !!s.canViewAnalysis,
    canUploadDocs: !!s.canUploadDocs,
    status: shareStatus(s, now),
    acceptedAt: s.acceptedAt ?? null,
    expiresAt: s.invitationExpiresAt ?? null,
    acceptUrl: s.acceptUrl ?? null,
    inviteToken: s.invitationToken ?? null,
  };
}

/**
 * The invite token used to build an accept link: the explicit field when
 * present, else the last path segment of the backend-built acceptUrl.
 */
export function inviteToken(s: Pick<ShareDto, "invitationToken" | "acceptUrl">): string | null {
  if (s.invitationToken) return s.invitationToken;
  const seg = s.acceptUrl?.split("/").filter(Boolean).pop();
  return seg || null;
}

/** Whole days until the invite expires (rounded up), or null if unknown or already past. */
export function inviteExpiresInDays(
  iso: string | null | undefined,
  now: number = Date.now(),
): number | null {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - now;
  if (!Number.isFinite(ms) || ms <= 0) return null;
  return Math.ceil(ms / 86_400_000);
}
