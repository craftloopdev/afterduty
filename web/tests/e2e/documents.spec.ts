import { test, expect } from "@playwright/test";

const vis = (loc: ReturnType<import("@playwright/test").Page["getByText"]>) =>
  loc.filter({ visible: true }).first();

test.describe("Documents — unauthenticated visitor", () => {
  test("/documents redirects to /login; upload route requires auth", async ({ page, request }) => {
    await page.goto("/documents");
    expect(page.url()).toContain("/login");
    const up = await request.post("/api/upload");
    expect(up.status()).toBe(401);
  });
});

test.describe("Documents — dev fixtures", () => {
  test("grid + upload card render", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/documents");
    await expect(vis(page.getByText("dd214.pdf"))).toBeVisible();
    await expect(vis(page.getByText("Upload documents"))).toBeVisible();
    await expect(vis(page.getByText("Processed"))).toBeVisible();
    expect(errors, errors.join("\n")).toHaveLength(0);
  });
});
