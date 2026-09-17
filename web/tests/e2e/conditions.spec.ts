import { test, expect } from "@playwright/test";

const vis = (loc: ReturnType<import("@playwright/test").Page["getByText"]>) =>
  loc.filter({ visible: true }).first();

test.describe("Conditions — unauthenticated visitor", () => {
  test("/conditions and a detail route redirect to /login", async ({ page }) => {
    await page.goto("/conditions");
    expect(page.url()).toContain("/login");
    await page.goto("/conditions/93");
    expect(page.url()).toContain("/login");
  });
});

test.describe("Conditions — list (dev fixtures)", () => {
  test("filter chips narrow the list", async ({ page }) => {
    await page.goto("/dev/conditions");
    // Default filter is "ready": a ready condition shows, a needs-work one does not.
    await expect(vis(page.getByText("PTSD"))).toBeVisible();
    await expect(page.getByText("Sleep Apnea")).toHaveCount(0);
    // All → needs-work conditions appear.
    await page.getByRole("button", { name: /All/ }).first().click();
    await expect(vis(page.getByText("Sleep Apnea"))).toBeVisible();
    // Needs work → ready-only conditions drop out.
    await page.getByRole("button", { name: /Needs work/ }).first().click();
    await expect(vis(page.getByText("Sleep Apnea"))).toBeVisible();
    await expect(page.getByText("PTSD")).toHaveCount(0);
  });
});

test.describe("Conditions — detail (dev fixtures)", () => {
  test("shows triad assessment, legs, and rating", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/conditions/93"); // PTSD
    await expect(vis(page.getByText("PTSD"))).toBeVisible();
    await expect(page.getByText("Triad assessment")).toBeVisible();
    await expect(vis(page.getByText("Diagnosis"))).toBeVisible();
    await expect(vis(page.getByText("Nexus"))).toBeVisible();
    expect(errors, errors.join("\n")).toHaveLength(0);
  });
});
