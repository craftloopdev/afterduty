import type { ShareVM } from "@/lib/models/vm";

// One accepted analysis grant, a pending docs-only invite (re-copyable link),
// and an expired invite — exercises the P2-2 lifecycle rendering on /dev/share.
export const sharesFixture: ShareVM[] = [
  {
    id: 1,
    email: "rep@vso.org",
    canViewAnalysis: true,
    canUploadDocs: false,
    status: "accepted",
    acceptedAt: "2026-06-05T15:00:00Z",
  },
  {
    id: 2,
    email: "attorney@law.example",
    canViewAnalysis: false,
    canUploadDocs: true,
    status: "pending",
    expiresAt: new Date(Date.now() + 5 * 86_400_000).toISOString(),
    acceptUrl: "https://app.afterduty.app/accept-share/FIXTURE_TOKEN",
    inviteToken: "FIXTURE_TOKEN",
  },
  {
    id: 3,
    email: "old-invite@vso.org",
    canViewAnalysis: false,
    canUploadDocs: false,
    status: "expired",
    expiresAt: "2026-05-01T00:00:00Z",
  },
];
