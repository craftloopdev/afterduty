import { test, expect } from "@playwright/test";

const vis = (loc: ReturnType<import("@playwright/test").Page["getByText"]>) =>
  loc.filter({ visible: true }).first();

test.describe("Paywall — unauthenticated visitor", () => {
  test("/upgrade redirects to /login; subscription route requires auth", async ({ page, request }) => {
    await page.goto("/upgrade");
    expect(page.url()).toContain("/login");
    const res = await request.post("/api/subscription", { data: { action: "checkout", tier: "monthly" } });
    expect(res.status()).toBe(401);
  });
});

test.describe("Paywall — dev fixtures", () => {
  test("plans + features render", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/upgrade");
    await expect(vis(page.getByText("$11.99", { exact: false }))).toBeVisible();
    await expect(vis(page.getByText("$119.99", { exact: false }))).toBeVisible();
    await expect(vis(page.getByText("AI evidence extraction"))).toBeVisible();
    await expect(vis(page.getByRole("button", { name: /Subscribe/ }))).toBeVisible();
    expect(errors, errors.join("\n")).toHaveLength(0);
  });
});
