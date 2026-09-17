import { test, expect } from "@playwright/test";

const vis = (loc: ReturnType<import("@playwright/test").Page["getByText"]>) =>
  loc.filter({ visible: true }).first();

test.describe("Next Steps — unauthenticated visitor", () => {
  test("/steps redirects to /login", async ({ page }) => {
    await page.goto("/steps");
    expect(page.url()).toContain("/login");
  });
});

test.describe("Next Steps — dev fixtures", () => {
  test("steps list shows gaps; Scenarios tab shows computed pay", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/steps");
    await expect(vis(page.getByText("Missing nexus letter"))).toBeVisible();

    await page.getByRole("tab", { name: "Scenarios" }).click();
    await expect(vis(page.getByText("$3,831"))).toBeVisible();
    await expect(vis(page.getByText(/ready condition/))).toBeVisible();

    expect(errors, errors.join("\n")).toHaveLength(0);
  });
});
