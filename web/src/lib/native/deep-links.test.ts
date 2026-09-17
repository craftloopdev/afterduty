import { describe, it, expect } from "vitest";
import { mapDeepLink, isPublicDest } from "./deep-links";

// capacitor-ios-spec §D.3/§G.3 — the deep-link path mapper. Universal links and
// the custom scheme must resolve to the native (query-param twin) routes.

describe("mapDeepLink", () => {
  it("sends a retired magic-link completion URL home (OTP has no inbound link)", () => {
    // Old sign-in emails may still be lying around; their links expired within
    // the hour, so landing home (not a dead route) is the right degrade.
    expect(
      mapDeepLink("https://app.afterduty.app/finish-sign-in?apiKey=x&oobCode=y"),
    ).toBe("/");
  });

  it("maps a share universal link to the query-param twin (§A.3a)", () => {
    expect(mapDeepLink("https://app.afterduty.app/accept-share/TOKEN123")).toBe(
      "/accept-share?token=TOKEN123",
    );
  });

  it("passes through an already-twinned accept-share link", () => {
    expect(mapDeepLink("https://app.afterduty.app/accept-share?token=ABC")).toBe(
      "/accept-share?token=ABC",
    );
  });

  it("maps a condition detail link to the query-param twin", () => {
    expect(mapDeepLink("https://app.afterduty.app/conditions/42")).toBe(
      "/conditions/detail?id=42",
    );
  });

  it("sends unknown paths and the reopen bridge to home", () => {
    expect(mapDeepLink("afterduty://signed-in")).toBe("/");
    expect(mapDeepLink("https://app.afterduty.app/whatever")).toBe("/");
  });

  it("ignores malformed URLs", () => {
    expect(mapDeepLink("not a url")).toBeNull();
  });

  it("tolerates an engine exposing a custom-scheme path with extra leading slashes", () => {
    // The Android WebView parses "afterduty:///dev/x" as pathname "///dev/x"
    // (opaque URL); Node yields "//dev/x" for a four-slash form, which is the
    // closest reproduction here. Either way the mapper must land on /dev/x.
    expect(mapDeepLink("afterduty:////dev/home/populated", { allowDev: true })).toBe(
      "/dev/home/populated",
    );
    expect(mapDeepLink("afterduty:////dev/home/populated")).toBe("/");
  });

  it("only resolves /dev/* links when allowDev is set (capture builds — §F.3)", () => {
    expect(mapDeepLink("afterduty:///dev/home/populated")).toBe("/");
    expect(mapDeepLink("afterduty:///dev/home/populated", { allowDev: true })).toBe(
      "/dev/home/populated",
    );
  });
});

// The native auth gate must not clobber a cold deep link into a pre-auth public
// flow with its signed-out `/login` redirect (dim 6, §B.5/§A.3a). `isPublicDest`
// classifies the mapper's OUTPUT (twin forms, query included) so the gate can
// replay it instead of bouncing to /login.
describe("isPublicDest", () => {
  it("treats share-accept (twin + canonical) as public", () => {
    expect(isPublicDest("/accept-share?token=ABC")).toBe(true);
    expect(isPublicDest("/accept-share/TOKEN123")).toBe(true);
  });

  it("treats authed/app destinations as NOT public (so they still gate to /login)", () => {
    expect(isPublicDest("/")).toBe(false);
    expect(isPublicDest("/conditions/detail?id=42")).toBe(false);
    expect(isPublicDest("/login")).toBe(false);
    // Guard against a naive substring match leaking a lookalike path.
    expect(isPublicDest("/accept-share-not-really")).toBe(false);
  });

  it("matches every mapper public output (mapper/gate stay in sync)", () => {
    expect(isPublicDest(mapDeepLink("https://app.afterduty.app/accept-share/T")!)).toBe(
      true,
    );
  });
});
