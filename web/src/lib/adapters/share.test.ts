import { describe, it, expect } from "vitest";
import { inviteExpiresInDays, inviteToken, shareStatus, toShare } from "./share";

const NOW = Date.parse("2026-06-07T00:00:00Z");

describe("shareStatus", () => {
  it("revoked wins over everything", () => {
    expect(shareStatus({ id: 1, revokedAt: "2026-01-01T00:00:00Z", acceptedAt: "2026-01-01T00:00:00Z" }, NOW)).toBe("revoked");
  });
  it("accepted when acceptedAt set and not revoked", () => {
    expect(shareStatus({ id: 1, acceptedAt: "2026-01-01T00:00:00Z" }, NOW)).toBe("accepted");
  });
  it("expired when the invite lapsed and was never accepted", () => {
    expect(shareStatus({ id: 1, invitationExpiresAt: "2026-01-01T00:00:00Z" }, NOW)).toBe("expired");
  });
  it("pending otherwise", () => {
    expect(shareStatus({ id: 1, invitationExpiresAt: "2027-01-01T00:00:00Z" }, NOW)).toBe("pending");
    expect(shareStatus({ id: 1 }, NOW)).toBe("pending");
  });
  it("prefers the backend-computed status over date arithmetic (P2-2)", () => {
    // The server owns the precedence rules — trust it even when local dates disagree.
    expect(shareStatus({ id: 1, status: "expired", invitationExpiresAt: "2027-01-01T00:00:00Z" }, NOW)).toBe("expired");
    expect(shareStatus({ id: 1, status: "revoked" }, NOW)).toBe("revoked");
    // Unknown server values fall back to the date-derived computation.
    expect(shareStatus({ id: 1, status: "weird", acceptedAt: "2026-01-01T00:00:00Z" }, NOW)).toBe("accepted");
  });
});

describe("toShare", () => {
  it("maps fields and coerces flags", () => {
    const vm = toShare({ id: 7, viewerEmail: "a@b.com", canViewAnalysis: true }, NOW);
    expect(vm).toEqual({
      id: 7,
      email: "a@b.com",
      canViewAnalysis: true,
      canUploadDocs: false,
      status: "pending",
      acceptedAt: null,
      expiresAt: null,
      acceptUrl: null,
      inviteToken: null,
    });
  });
  it("carries the lifecycle dates and re-copyable link (P2-2)", () => {
    const vm = toShare(
      {
        id: 8,
        viewerEmail: "rep@vso.org",
        status: "pending",
        invitationToken: "TOK9",
        acceptUrl: "https://app.afterduty.app/accept-share/TOK9",
        invitationExpiresAt: "2026-06-12T00:00:00Z",
      },
      NOW,
    );
    expect(vm.expiresAt).toBe("2026-06-12T00:00:00Z");
    expect(vm.acceptUrl).toBe("https://app.afterduty.app/accept-share/TOK9");
    expect(vm.inviteToken).toBe("TOK9");
    expect(vm.acceptedAt).toBeNull();
  });
});

describe("inviteToken", () => {
  it("prefers the explicit invitationToken field", () => {
    expect(inviteToken({ invitationToken: "tok123", acceptUrl: "https://x/accept-share/other" })).toBe("tok123");
  });
  it("falls back to the last path segment of acceptUrl", () => {
    expect(inviteToken({ acceptUrl: "https://app.afterduty.app/accept-share/abc_DEF-9" })).toBe("abc_DEF-9");
    expect(inviteToken({ acceptUrl: "https://app.afterduty.app/accept-share/abc/" })).toBe("abc");
  });
  it("returns null when neither is available", () => {
    expect(inviteToken({})).toBeNull();
    expect(inviteToken({ invitationToken: null, acceptUrl: null })).toBeNull();
    expect(inviteToken({ acceptUrl: "" })).toBeNull();
  });
});

describe("inviteExpiresInDays", () => {
  it("rounds up to whole days", () => {
    expect(inviteExpiresInDays("2026-06-14T00:00:00Z", NOW)).toBe(7);
    expect(inviteExpiresInDays("2026-06-07T06:00:00Z", NOW)).toBe(1);
  });
  it("returns null for past, missing, or bogus dates", () => {
    expect(inviteExpiresInDays("2026-06-01T00:00:00Z", NOW)).toBeNull();
    expect(inviteExpiresInDays(null, NOW)).toBeNull();
    expect(inviteExpiresInDays(undefined, NOW)).toBeNull();
    expect(inviteExpiresInDays("not-a-date", NOW)).toBeNull();
  });
});
