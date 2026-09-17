import { test, expect } from "@playwright/test";

const vis = (loc: ReturnType<import("@playwright/test").Page["getByText"]>) =>
  loc.filter({ visible: true }).first();

test.describe("Profile — unauthenticated visitor", () => {
  test("/profile redirects to /login; account-delete route requires auth", async ({ page, request }) => {
    await page.goto("/profile");
    expect(page.url()).toContain("/login");
    const del = await request.delete("/api/account");
    expect(del.status()).toBe(401);
  });
});

test.describe("Profile — dev fixtures", () => {
  test("renders service summary, appearance controls, and account actions", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/profile");
    await expect(vis(page.getByText("Service summary"))).toBeVisible();
    await expect(vis(page.getByText("Appearance"))).toBeVisible();
    await expect(vis(page.getByText("Dark mode"))).toBeVisible();
    await expect(vis(page.getByRole("button", { name: /Sign out/ }))).toBeVisible();
    await expect(vis(page.getByRole("button", { name: /Delete account/ }))).toBeVisible();
    expect(errors, errors.join("\n")).toHaveLength(0);
  });
});
