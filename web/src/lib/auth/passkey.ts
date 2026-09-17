"use client";

// The browser WebAuthn ceremony (auth-program-plan P1.3, web passkeys). This is
// the ONLY place that touches `navigator.credentials`. It translates between the
// server's base64url-JSON ceremony options (the PINNED contract shape) and the
// ArrayBuffer-typed structures the platform API demands, then serializes the
// resulting credential back to base64url JSON so the RP (Spring, via the BFF)
// can verify it.
//
// PROGRESSIVE ENHANCEMENT is load-bearing: every entry point is feature-detected
// and the caller (driver.web.ts) wraps each call in try/catch, so a browser
// without WebAuthn — or a user who cancels the OS prompt — behaves exactly like
// today (the OTP flow is never blocked). We DON'T verify anything here: signature,
// challenge single-use/TTL, origin, rpId hash, sign_count monotonicity, and UV
// policy are all enforced server-side by the RP's WebAuthn library. This module
// only carries the ceremony bytes across the boundary.

// ── base64url <-> ArrayBuffer ────────────────────────────────────────────────
// WebAuthn wire JSON is base64url (RFC 4648 §5, no padding). `atob/btoa` speak
// standard base64, so we translate the alphabet + padding on the way in/out.

/** Decode a base64url string to an ArrayBuffer. Tolerates standard-base64 input
 *  (+//=) too, so it round-trips whatever we produced. */
export function base64urlToBuffer(value: string): ArrayBuffer {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), "=");
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

/** Encode an ArrayBuffer (or view) to a base64url string, no padding. */
export function bufferToBase64url(value: ArrayBuffer | ArrayBufferView): string {
  const bytes =
    value instanceof ArrayBuffer
      ? new Uint8Array(value)
      : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// ── Feature detection ────────────────────────────────────────────────────────

/** True when this browser exposes the WebAuthn platform API at all. SSR-safe
 *  (returns false when `window`/`navigator` are absent). */
export function isWebAuthnSupported(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.PublicKeyCredential !== "undefined" &&
    typeof navigator !== "undefined" &&
    !!navigator.credentials &&
    typeof navigator.credentials.get === "function" &&
    typeof navigator.credentials.create === "function"
  );
}

/** True when conditional mediation ("passkey autofill") is available. Used as a
 *  capability probe before offering a passkey; never throws (a browser without
 *  the static method simply reports false). */
export async function isConditionalMediationAvailable(): Promise<boolean> {
  try {
    if (!isWebAuthnSupported()) return false;
    const pkc = window.PublicKeyCredential as typeof PublicKeyCredential & {
      isConditionalMediationAvailable?: () => Promise<boolean>;
    };
    if (typeof pkc.isConditionalMediationAvailable !== "function") return false;
    return await pkc.isConditionalMediationAvailable();
  } catch {
    return false;
  }
}

// ── Server option shapes (the PINNED ceremony contract, base64url on the wire) ─
// Only the fields the ceremony needs are typed; unknown fields pass through the
// spread so a server addition doesn't require a change here.

interface ServerCredentialDescriptor {
  type: "public-key";
  id: string; // base64url
  transports?: AuthenticatorTransport[];
}

/** PublicKeyCredentialCreationOptions with base64url `challenge`, `user.id`, and
 *  `excludeCredentials[].id` (register/options response). */
export interface RegistrationOptionsJSON {
  challenge: string;
  rp: { id?: string; name: string };
  user: { id: string; name: string; displayName: string };
  pubKeyCredParams: PublicKeyCredentialParameters[];
  timeout?: number;
  attestation?: AttestationConveyancePreference;
  excludeCredentials?: ServerCredentialDescriptor[];
  authenticatorSelection?: AuthenticatorSelectionCriteria;
}

/** PublicKeyCredentialRequestOptions with base64url `challenge` and
 *  `allowCredentials[].id` (assert/options response). */
export interface AuthenticationOptionsJSON {
  challenge: string;
  rpId?: string;
  timeout?: number;
  userVerification?: UserVerificationRequirement;
  allowCredentials?: ServerCredentialDescriptor[];
}

// ── Serialized ceremony results (base64url; what the RP verifies) ─────────────

/** attestation response body → register/verify. Mirrors the standard
 *  `PublicKeyCredential.toJSON()` attestation shape (all bytes base64url). */
export interface RegistrationCredentialJSON {
  id: string;
  rawId: string;
  type: "public-key";
  response: {
    clientDataJSON: string;
    attestationObject: string;
    transports?: string[];
  };
  clientExtensionResults: AuthenticationExtensionsClientOutputs;
}

/** assertion response body → assert/verify. */
export interface AuthenticationCredentialJSON {
  id: string;
  rawId: string;
  type: "public-key";
  response: {
    clientDataJSON: string;
    authenticatorData: string;
    signature: string;
    userHandle: string | null;
  };
  clientExtensionResults: AuthenticationExtensionsClientOutputs;
}

// ── Option decoders ──────────────────────────────────────────────────────────

function decodeDescriptors(
  list: ServerCredentialDescriptor[] | undefined,
): PublicKeyCredentialDescriptor[] | undefined {
  if (!list?.length) return list ? [] : undefined;
  return list.map((d) => ({
    type: d.type,
    id: base64urlToBuffer(d.id),
    ...(d.transports ? { transports: d.transports } : {}),
  }));
}

function toCreationOptions(json: RegistrationOptionsJSON): PublicKeyCredentialCreationOptions {
  return {
    ...json,
    challenge: base64urlToBuffer(json.challenge),
    user: {
      ...json.user,
      id: base64urlToBuffer(json.user.id),
    },
    excludeCredentials: decodeDescriptors(json.excludeCredentials),
  };
}

function toRequestOptions(json: AuthenticationOptionsJSON): PublicKeyCredentialRequestOptions {
  return {
    ...json,
    challenge: base64urlToBuffer(json.challenge),
    allowCredentials: decodeDescriptors(json.allowCredentials),
  };
}

// ── Ceremonies ───────────────────────────────────────────────────────────────

/**
 * Run the REGISTER ceremony: hand the decoded creation options to
 * `navigator.credentials.create` and serialize the attestation for
 * register/verify. Throws if WebAuthn is unsupported or the OS returns no
 * credential (cancel) — the caller (driver.web.ts) catches and falls back.
 */
export async function createPasskey(
  options: RegistrationOptionsJSON,
): Promise<RegistrationCredentialJSON> {
  if (!isWebAuthnSupported()) throw new Error("webauthn-unsupported");
  const credential = (await navigator.credentials.create({
    publicKey: toCreationOptions(options),
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error("webauthn-no-credential");

  const response = credential.response as AuthenticatorAttestationResponse;
  const transports =
    typeof response.getTransports === "function" ? response.getTransports() : undefined;

  return {
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    type: "public-key",
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      attestationObject: bufferToBase64url(response.attestationObject),
      ...(transports && transports.length ? { transports } : {}),
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}

/**
 * Run the AUTHENTICATE ceremony: hand the decoded request options to
 * `navigator.credentials.get` and serialize the assertion for assert/verify.
 * Throws if WebAuthn is unsupported or the OS returns no credential (cancel) —
 * the caller catches and falls back to OTP so login is never blocked.
 */
export async function getPasskeyAssertion(
  options: AuthenticationOptionsJSON,
): Promise<AuthenticationCredentialJSON> {
  if (!isWebAuthnSupported()) throw new Error("webauthn-unsupported");
  const credential = (await navigator.credentials.get({
    publicKey: toRequestOptions(options),
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error("webauthn-no-credential");

  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    type: "public-key",
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      authenticatorData: bufferToBase64url(response.authenticatorData),
      signature: bufferToBase64url(response.signature),
      userHandle: response.userHandle ? bufferToBase64url(response.userHandle) : null,
    },
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}
