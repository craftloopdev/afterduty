import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import path from "node:path";

// Playwright runs with cwd = web/. Repo root is one level up.
const WEB_ROOT = process.cwd();
const AUTH_DIR = path.join(WEB_ROOT, ".auth");
const VET_STATE = path.join(AUTH_DIR, "veteran.json");
const MINT_SCRIPT = path.join(WEB_ROOT, "..", "tests", "mint_token.py");
const SESSION_COOKIE = "cp_session";

/**
 * Best-effort: mint a REAL Firebase ID token (reusing the repo's
 * tests/mint_token.py) and exchange it for a BFF session cookie, saved as a
 * Playwright storageState. If creds aren't present, the authed spec self-skips.
 *
 * Required env to enable: GOOGLE_APPLICATION_CREDENTIALS, FIREBASE_API_KEY,
 * FIREBASE_PROJECT_ID, FIREBASE_TEST_UID (+ FIREBASE_TEST_EMAIL).
 */
export default async function globalSetup() {
  rmSync(VET_STATE, { force: true });

  if (!process.env.GOOGLE_APPLICATION_CREDENTIALS || !process.env.FIREBASE_TEST_UID) {
    console.log("[global-setup] No Firebase test creds — authed e2e will be skipped.");
    return;
  }

  const baseURL = process.env.VCP_BASE_URL ?? `http://localhost:${process.env.PORT ?? "3100"}`;

  try {
    const idToken = execFileSync("python3", [MINT_SCRIPT], { encoding: "utf8" }).trim();
    if (!idToken || idToken.length < 20) throw new Error("mint_token.py returned no token");

    const res = await fetch(`${baseURL}/api/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken }),
    });
    if (!res.ok) throw new Error(`/api/session returned ${res.status}`);

    const url = new URL(baseURL);
    const state = {
      cookies: [
        {
          name: SESSION_COOKIE,
          value: idToken,
          domain: url.hostname,
          path: "/",
          httpOnly: true,
          secure: url.protocol === "https:",
          sameSite: "Lax" as const,
          expires: Math.floor(Date.now() / 1000) + 3300,
        },
      ],
      origins: [],
    };
    mkdirSync(AUTH_DIR, { recursive: true });
    writeFileSync(VET_STATE, JSON.stringify(state, null, 2));
    console.log("[global-setup] Minted real token → .auth/veteran.json");
  } catch (e) {
    console.warn(`[global-setup] Token mint failed; authed e2e will skip. ${(e as Error).message}`);
  }
}
