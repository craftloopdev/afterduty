import { test, expect } from "@playwright/test";

const vis = (loc: ReturnType<import("@playwright/test").Page["getByText"]>) =>
  loc.filter({ visible: true }).first();

test.describe("Share — unauthenticated visitor", () => {
  test("/share redirects to /login; share route requires auth", async ({ page, request }) => {
    await page.goto("/share");
    expect(page.url()).toContain("/login");
    const res = await request.post("/api/share", { data: { viewerEmail: "x@y.com" } });
    expect(res.status()).toBe(401);
  });
});

test.describe("Share — dev fixtures", () => {
  test("invite form + existing shares render", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/share");
    await expect(vis(page.getByText("Share analysis access"))).toBeVisible();
    await expect(vis(page.getByRole("button", { name: /Send secure invite/ }))).toBeVisible();
    await expect(vis(page.getByText("rep@vso.org"))).toBeVisible();
    expect(errors, errors.join("\n")).toHaveLength(0);
  });
});
