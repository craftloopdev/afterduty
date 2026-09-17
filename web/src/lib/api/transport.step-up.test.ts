import { describe, it, expect } from "vitest";
import { mapResponse, readStepUpChallenge } from "./transport";
import { ForbiddenError, StepUpRequiredError } from "./errors";

// The 403 fork (auth-program-plan P1.2): a `403 {code:"step_up_required"}` must
// surface as the TYPED StepUpRequiredError (so RSC reads can act + client
// mutations run the ceremony); any OTHER 403 stays a plain ForbiddenError.

function res(status: number, body?: unknown): Response {
  const init: ResponseInit = { status };
  return new Response(body === undefined ? null : JSON.stringify(body), init);
}

describe("mapResponse — step-up 403 fork", () => {
  it("throws StepUpRequiredError on 403 {code:step_up_required} with acceptedFactors", async () => {
    const r = res(403, { code: "step_up_required", acceptedFactors: ["otp"] });
    await expect(mapResponse(r, "/shares", {})).rejects.toMatchObject({
      name: "StepUpRequiredError",
      acceptedFactors: ["otp"],
      path: "/shares",
    });
  });

  it("defaults acceptedFactors to ['otp'] when the field is missing", async () => {
    const r = res(403, { code: "step_up_required" });
    try {
      await mapResponse(r, "/x", {});
      throw new Error("should have thrown");
    } catch (e) {
      expect(e).toBeInstanceOf(StepUpRequiredError);
      expect((e as StepUpRequiredError).acceptedFactors).toEqual(["otp"]);
    }
  });

  it("throws plain ForbiddenError on a 403 that is NOT a step-up challenge", async () => {
    const r = res(403, { error: "forbidden" });
    await expect(mapResponse(r, "/y", {})).rejects.toBeInstanceOf(ForbiddenError);
  });

  it("throws plain ForbiddenError on a non-JSON / empty 403 body", async () => {
    await expect(mapResponse(res(403), "/z", {})).rejects.toBeInstanceOf(ForbiddenError);
  });

  it("still maps the non-403 statuses unchanged (204 → null, ok → parsed)", async () => {
    expect(await mapResponse(res(204), "/a", {})).toBeNull();
    expect(await mapResponse(res(200, { ok: true }), "/b", {})).toEqual({ ok: true });
  });
});

describe("readStepUpChallenge", () => {
  it("returns the challenge for a step-up body", async () => {
    const c = await readStepUpChallenge(res(403, { code: "step_up_required", acceptedFactors: ["otp"] }));
    expect(c).toEqual({ acceptedFactors: ["otp"] });
  });

  it("returns null for a non-step-up body", async () => {
    expect(await readStepUpChallenge(res(403, { code: "nope" }))).toBeNull();
    expect(await readStepUpChallenge(res(403))).toBeNull();
  });
});
