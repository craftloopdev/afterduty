package com.afterduty.service.webauthn;

import com.upokecenter.cbor.CBORObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * A minimal deterministic software WebAuthn authenticator for tests (auth program
 * P1.3). Produces {@code navigator.credentials.create()/get()}-shaped JSON that
 * the Yubico verifier accepts and verifies — an ES256 (P-256) credential with a
 * {@code "none"} attestation, so a full register-then-assert round trip runs
 * entirely in-process without a browser or hardware.
 *
 * <p>Not production code — it lives under test/ and exists solely to exercise the
 * RP verifier with realistic, correctly-signed ceremony responses (and to forge
 * BAD ones: a sign-count regression, a wrong origin, a wrong rpId).
 */
public final class SoftwareAuthenticator {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final KeyPair keyPair;
    /** 16-byte AAGUID (all zero, as many platform authenticators report). */
    private final byte[] aaguid = new byte[16];
    /** A stable random credential id. */
    private final byte[] credentialId;
    private long signCount;

    public SoftwareAuthenticator() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.credentialId = new byte[32];
        // Deterministic-ish per instance is fine; content only needs to be stable
        // within one register→assert round trip and unique across authenticators.
        new java.security.SecureRandom().nextBytes(this.credentialId);
        this.signCount = 0;
    }

    public String credentialIdB64Url() {
        return B64URL.encodeToString(credentialId);
    }

    // ---- registration --------------------------------------------------------

    /**
     * Build the attestation response JSON for a create() ceremony.
     *
     * @param challengeB64Url the challenge from the creation options
     * @param origin          the client origin to embed in clientDataJSON
     * @param rpId            the rpId whose SHA-256 goes in authData
     */
    public String makeCreateResponseJson(String challengeB64Url, String origin, String rpId) {
        byte[] clientDataJson = clientData("webauthn.create", challengeB64Url, origin);
        byte[] authData = authenticatorData(rpId, /*includeAttestedCred=*/true, 0);
        byte[] attestationObject = noneAttestationObject(authData);

        String json = "{"
                + "\"type\":\"public-key\","
                + "\"id\":\"" + credentialIdB64Url() + "\","
                + "\"rawId\":\"" + credentialIdB64Url() + "\","
                + "\"response\":{"
                + "\"clientDataJSON\":\"" + b64url(clientDataJson) + "\","
                + "\"attestationObject\":\"" + b64url(attestationObject) + "\""
                + "},"
                + "\"clientExtensionResults\":{}"
                + "}";
        return json;
    }

    // ---- assertion -----------------------------------------------------------

    /** Assertion response JSON, advancing the sign counter by one (the honest case). */
    public String makeGetResponseJson(String challengeB64Url, String origin, String rpId, String userHandleB64Url) {
        long next = ++signCount;
        return makeGetResponseJson(challengeB64Url, origin, rpId, userHandleB64Url, next);
    }

    /**
     * Assertion response JSON with an EXPLICIT sign count — lets a test force a
     * regression (a count &lt;= the stored one) to prove the verifier rejects a
     * cloned authenticator.
     */
    public String makeGetResponseJson(String challengeB64Url, String origin, String rpId,
                                      String userHandleB64Url, long explicitSignCount) {
        byte[] clientDataJson = clientData("webauthn.get", challengeB64Url, origin);
        byte[] authData = authenticatorData(rpId, /*includeAttestedCred=*/false, explicitSignCount);

        byte[] signature = signAssertion(authData, clientDataJson);

        StringBuilder json = new StringBuilder();
        json.append("{")
                .append("\"type\":\"public-key\",")
                .append("\"id\":\"").append(credentialIdB64Url()).append("\",")
                .append("\"rawId\":\"").append(credentialIdB64Url()).append("\",")
                .append("\"response\":{")
                .append("\"clientDataJSON\":\"").append(b64url(clientDataJson)).append("\",")
                .append("\"authenticatorData\":\"").append(b64url(authData)).append("\",")
                .append("\"signature\":\"").append(b64url(signature)).append("\"");
        if (userHandleB64Url != null) {
            json.append(",\"userHandle\":\"").append(userHandleB64Url).append("\"");
        }
        json.append("},\"clientExtensionResults\":{}}");
        return json.toString();
    }

    // ---- internals -----------------------------------------------------------

    private byte[] clientData(String type, String challengeB64Url, String origin) {
        // The challenge must be echoed exactly (base64url, no padding) — the
        // verifier compares it against the stored challenge.
        String json = "{"
                + "\"type\":\"" + type + "\","
                + "\"challenge\":\"" + challengeB64Url + "\","
                + "\"origin\":\"" + origin + "\","
                + "\"crossOrigin\":false"
                + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * authenticatorData = rpIdHash(32) || flags(1) || signCount(4)
     *                     [|| attestedCredentialData when includeAttestedCred].
     * flags: UP (0x01) + UV (0x04) [+ AT (0x40) for registration].
     */
    private byte[] authenticatorData(String rpId, boolean includeAttestedCred, long count) {
        byte[] rpIdHash = sha256(rpId.getBytes(StandardCharsets.UTF_8));
        int flags = 0x01 | 0x04; // UP + UV
        if (includeAttestedCred) flags |= 0x40; // AT

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(rpIdHash);
        out.write(flags);
        // 4-byte big-endian sign count.
        out.write((int) ((count >> 24) & 0xff));
        out.write((int) ((count >> 16) & 0xff));
        out.write((int) ((count >> 8) & 0xff));
        out.write((int) (count & 0xff));

        if (includeAttestedCred) {
            out.writeBytes(aaguid);
            // credentialId length (2-byte big-endian) + credentialId.
            out.write((credentialId.length >> 8) & 0xff);
            out.write(credentialId.length & 0xff);
            out.writeBytes(credentialId);
            out.writeBytes(coseKey());
        }
        return out.toByteArray();
    }

    /** COSE_Key (EC2, ES256, P-256) CBOR for the public key. */
    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) keyPair.getPublic();
        byte[] x = toFixed32(pub.getW().getAffineX().toByteArray());
        byte[] y = toFixed32(pub.getW().getAffineY().toByteArray());

        CBORObject cose = CBORObject.NewMap();
        cose.set(CBORObject.FromObject(1), CBORObject.FromObject(2));   // kty: EC2
        cose.set(CBORObject.FromObject(3), CBORObject.FromObject(-7));  // alg: ES256
        cose.set(CBORObject.FromObject(-1), CBORObject.FromObject(1));  // crv: P-256
        cose.set(CBORObject.FromObject(-2), CBORObject.FromObject(x));  // x
        cose.set(CBORObject.FromObject(-3), CBORObject.FromObject(y));  // y
        return cose.EncodeToBytes();
    }

    private byte[] noneAttestationObject(byte[] authData) {
        CBORObject obj = CBORObject.NewMap();
        obj.set(CBORObject.FromObject("fmt"), CBORObject.FromObject("none"));
        obj.set(CBORObject.FromObject("attStmt"), CBORObject.NewMap());
        obj.set(CBORObject.FromObject("authData"), CBORObject.FromObject(authData));
        return obj.EncodeToBytes();
    }

    private byte[] signAssertion(byte[] authData, byte[] clientDataJson) {
        try {
            byte[] signedData = concat(authData, sha256(clientDataJson));
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign((ECPrivateKey) keyPair.getPrivate());
            sig.update(signedData);
            return sig.sign(); // DER-encoded ECDSA signature — what WebAuthn expects.
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- byte helpers --------------------------------------------------------

    static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Left-pad / trim a BigInteger's two's-complement bytes to a fixed 32-byte field. */
    private static byte[] toFixed32(byte[] raw) {
        if (raw.length == 32) return raw;
        byte[] out = new byte[32];
        if (raw.length > 32) {
            // Drop a leading sign byte (0x00).
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static String b64url(byte[] in) {
        return B64URL.encodeToString(in);
    }

    public static byte[] decodeB64Url(String s) {
        return B64URL_DEC.decode(s);
    }
}
