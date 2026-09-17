import { test, expect } from "@playwright/test";
import fs from "node:fs";

const VET_STATE = ".auth/veteran.json";

// ── Correct actor: unauthenticated visitor ──────────────────────────────
test.describe("Home — unauthenticated visitor", () => {
  test("hitting / redirects to /login and leaks no claim content", async ({ page }) => {
    await page.goto("/");
    expect(page.url()).toContain("/login");
    await expect(page.getByText(/Welcome back/)).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Continue with Google/ })).toBeVisible();
  });

  test("BFF session route validates input", async ({ request }) => {
    const bad = await request.post("/api/session", { data: {} });
    expect(bad.status()).toBe(400);
    const ok = await request.post("/api/session", { data: { idToken: "x".repeat(40) } });
    expect(ok.status()).toBe(201);
  });
});

// ── Deterministic render pipeline (fixtures → adapter → UI, no auth) ─────
test.describe("Home — dev fixtures", () => {
  test("populated renders rating, pay, conditions & evidence health (no console errors)", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/home/populated");
    await expect(page.getByText("100", { exact: false }).first()).toBeVisible();
    await expect(page.getByText("$3,831", { exact: false }).first()).toBeVisible();
    // PTSD renders in both the desktop table and the mobile glance (both in DOM,
    // one hidden via @media) — assert the visible one for this viewport.
    await expect(page.getByText("PTSD").filter({ visible: true }).first()).toBeVisible();
    await expect(page.getByText("Missing nexus letter").first()).toBeVisible();
    await expect(page.getByText("Evidence health")).toBeVisible();
    expect(errors, errors.join("\n")).toHaveLength(0);
  });

  test("empty shows the onboarding state", async ({ page }) => {
    await page.goto("/dev/home/empty");
    await expect(page.getByText("Let's build your claim")).toBeVisible();
    await expect(page.getByText(/Add your evidence/)).toBeVisible();
  });

  test("analyzing shows the progress state", async ({ page }) => {
    await page.goto("/dev/home/analyzing");
    await expect(page.getByText("We're analyzing your records")).toBeVisible();
    await expect(page.getByText("45%")).toBeVisible();
  });
});

// ── Correct actor: authenticated veteran (real minted token; skips w/o creds) ──
const hasAuth = fs.existsSync(VET_STATE);
test.describe("Home — authenticated veteran", () => {
  test.skip(!hasAuth, "no minted token (set FIREBASE_TEST_* + GOOGLE_APPLICATION_CREDENTIALS)");
  test.use({ storageState: hasAuth ? VET_STATE : undefined });

  test("renders the real claim instead of redirecting", async ({ page }) => {
    await page.goto("/");
    expect(page.url()).not.toContain("/login");
    await expect(page.getByText(/Welcome back/)).toBeVisible();
  });
});
