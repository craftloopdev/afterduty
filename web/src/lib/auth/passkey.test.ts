import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  base64urlToBuffer,
  bufferToBase64url,
  isWebAuthnSupported,
  isConditionalMediationAvailable,
  createPasskey,
  getPasskeyAssertion,
  type RegistrationOptionsJSON,
  type AuthenticationOptionsJSON,
} from "./passkey";

// The browser WebAuthn ceremony seam (auth-program-plan P1.3). We verify the
// base64url<->buffer round-trip (the wire encoding the RP verifies against) and
// that the ceremonies decode server options into ArrayBuffers, call
// navigator.credentials, and serialize the credential back to base64url JSON —
// with a MOCK authenticator (no real crypto; the RP owns verification).

describe("base64url <-> ArrayBuffer", () => {
  it("round-trips arbitrary bytes (incl. bytes that map to + and / in base64)", () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 251, 252, 253, 254, 255, 62, 63]);
    const encoded = bufferToBase64url(bytes);
    // base64url alphabet only — no +, /, or = padding.
    expect(encoded).not.toMatch(/[+/=]/);
    const decoded = new Uint8Array(base64urlToBuffer(encoded));
    expect(Array.from(decoded)).toEqual(Array.from(bytes));
  });

  it("decodes a known base64url challenge to its exact bytes", () => {
    // "hello" = 68 65 6c 6c 6f → base64url "aGVsbG8"
    const buf = base64urlToBuffer("aGVsbG8");
    expect(Array.from(new Uint8Array(buf))).toEqual([0x68, 0x65, 0x6c, 0x6c, 0x6f]);
  });

  it("tolerates standard-base64 input (+ / =) so it round-trips either alphabet", () => {
    const bytes = new Uint8Array([255, 254, 253]);
    const std = btoa(String.fromCharCode(...bytes)); // "//79" (contains /)
    expect(std).toContain("/");
    expect(Array.from(new Uint8Array(base64urlToBuffer(std)))).toEqual([255, 254, 253]);
  });

  it("accepts an ArrayBuffer or a typed-array view", () => {
    const backing = new Uint8Array([9, 8, 7]).buffer;
    expect(bufferToBase64url(backing)).toBe(bufferToBase64url(new Uint8Array([9, 8, 7])));
  });
});

describe("feature detection", () => {
  const original = globalThis.PublicKeyCredential;
  afterEach(() => {
    // Restore whatever jsdom had (usually undefined).
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = original;
    vi.restoreAllMocks();
  });

  it("isWebAuthnSupported is false when PublicKeyCredential is absent", () => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = undefined;
    expect(isWebAuthnSupported()).toBe(false);
  });

  it("isWebAuthnSupported is true when the API + navigator.credentials exist", () => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = function () {};
    vi.stubGlobal("navigator", {
      credentials: { create: vi.fn(), get: vi.fn() },
    });
    expect(isWebAuthnSupported()).toBe(true);
  });

  it("isConditionalMediationAvailable is false (never throws) without the static method", async () => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = function () {};
    vi.stubGlobal("navigator", { credentials: { create: vi.fn(), get: vi.fn() } });
    await expect(isConditionalMediationAvailable()).resolves.toBe(false);
  });

  it("isConditionalMediationAvailable reflects the platform probe when present", async () => {
    const pkc = function () {} as unknown as {
      isConditionalMediationAvailable: () => Promise<boolean>;
    };
    pkc.isConditionalMediationAvailable = vi.fn().mockResolvedValue(true);
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = pkc;
    vi.stubGlobal("navigator", { credentials: { create: vi.fn(), get: vi.fn() } });
    await expect(isConditionalMediationAvailable()).resolves.toBe(true);
  });
});

// ── Ceremony tests with a mock authenticator ─────────────────────────────────

const REG_OPTIONS: RegistrationOptionsJSON = {
  challenge: "aGVsbG8", // "hello"
  rp: { id: "afterduty.app", name: "After Duty" },
  user: { id: "dXNlcg", name: "vet@example.com", displayName: "Vet" }, // "user"
  pubKeyCredParams: [
    { type: "public-key", alg: -7 },
    { type: "public-key", alg: -257 },
  ],
  excludeCredentials: [{ type: "public-key", id: "ZXhpc3Rpbmc", transports: ["internal"] }],
};

const AUTH_OPTIONS: AuthenticationOptionsJSON = {
  challenge: "Y2hhbGxlbmdl", // "challenge"
  rpId: "afterduty.app",
  userVerification: "preferred",
  allowCredentials: [{ type: "public-key", id: "Y3JlZElk", transports: ["internal"] }], // "credId"
};

function bytes(str: string): ArrayBuffer {
  return new TextEncoder().encode(str).buffer;
}

describe("createPasskey (REGISTER ceremony)", () => {
  let create: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = function () {};
    create = vi.fn();
    vi.stubGlobal("navigator", { credentials: { create, get: vi.fn() } });
  });
  afterEach(() => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = undefined;
    vi.restoreAllMocks();
  });

  it("decodes the challenge/user.id/excludeCredentials to buffers and posts a base64url attestation", async () => {
    create.mockResolvedValue({
      id: "credId",
      rawId: bytes("credId"),
      type: "public-key",
      response: {
        clientDataJSON: bytes("cdj"),
        attestationObject: bytes("att"),
        getTransports: () => ["internal", "hybrid"],
      },
      getClientExtensionResults: () => ({}),
    });

    const result = await createPasskey(REG_OPTIONS);

    // The platform API received ArrayBuffer-typed fields, not the base64url strings.
    const passed = create.mock.calls[0][0].publicKey as PublicKeyCredentialCreationOptions;
    expect(passed.challenge).toBeInstanceOf(ArrayBuffer);
    expect(passed.user.id).toBeInstanceOf(ArrayBuffer);
    expect(new Uint8Array(passed.challenge as ArrayBuffer)).toEqual(
      new Uint8Array(base64urlToBuffer("aGVsbG8")),
    );
    expect(passed.excludeCredentials?.[0].id).toBeInstanceOf(ArrayBuffer);

    // The serialized attestation is base64url + carries transports.
    expect(result.type).toBe("public-key");
    expect(result.response.clientDataJSON).toBe(bufferToBase64url(bytes("cdj")));
    expect(result.response.attestationObject).toBe(bufferToBase64url(bytes("att")));
    expect(result.response.transports).toEqual(["internal", "hybrid"]);
  });

  it("throws when the OS returns no credential (user cancel)", async () => {
    create.mockResolvedValue(null);
    await expect(createPasskey(REG_OPTIONS)).rejects.toThrow();
  });

  it("throws webauthn-unsupported when the platform API is absent", async () => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = undefined;
    await expect(createPasskey(REG_OPTIONS)).rejects.toThrow(/unsupported/);
  });
});

describe("getPasskeyAssertion (AUTHENTICATE ceremony)", () => {
  let get: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = function () {};
    get = vi.fn();
    vi.stubGlobal("navigator", { credentials: { create: vi.fn(), get } });
  });
  afterEach(() => {
    (globalThis as { PublicKeyCredential?: unknown }).PublicKeyCredential = undefined;
    vi.restoreAllMocks();
  });

  it("decodes allowCredentials to buffers and serializes the assertion base64url", async () => {
    get.mockResolvedValue({
      id: "credId",
      rawId: bytes("credId"),
      type: "public-key",
      response: {
        clientDataJSON: bytes("cdj"),
        authenticatorData: bytes("authData"),
        signature: bytes("sig"),
        userHandle: bytes("user"),
      },
      getClientExtensionResults: () => ({}),
    });

    const result = await getPasskeyAssertion(AUTH_OPTIONS);

    const passed = get.mock.calls[0][0].publicKey as PublicKeyCredentialRequestOptions;
    expect(passed.challenge).toBeInstanceOf(ArrayBuffer);
    expect(passed.allowCredentials?.[0].id).toBeInstanceOf(ArrayBuffer);

    expect(result.response.authenticatorData).toBe(bufferToBase64url(bytes("authData")));
    expect(result.response.signature).toBe(bufferToBase64url(bytes("sig")));
    expect(result.response.userHandle).toBe(bufferToBase64url(bytes("user")));
  });

  it("serializes a null userHandle as null (discoverable creds may omit it)", async () => {
    get.mockResolvedValue({
      id: "credId",
      rawId: bytes("credId"),
      type: "public-key",
      response: {
        clientDataJSON: bytes("cdj"),
        authenticatorData: bytes("authData"),
        signature: bytes("sig"),
        userHandle: null,
      },
      getClientExtensionResults: () => ({}),
    });
    const result = await getPasskeyAssertion(AUTH_OPTIONS);
    expect(result.response.userHandle).toBeNull();
  });

  it("throws when the OS returns no credential (cancel)", async () => {
    get.mockResolvedValue(null);
    await expect(getPasskeyAssertion(AUTH_OPTIONS)).rejects.toThrow();
  });
});
