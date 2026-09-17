import { test, expect } from "@playwright/test";

const vis = (loc: ReturnType<import("@playwright/test").Page["getByText"]>) =>
  loc.filter({ visible: true }).first();

test.describe("Ask AI — unauthenticated visitor", () => {
  test("/ask redirects to /login; chat routes require auth", async ({ page, request }) => {
    await page.goto("/ask");
    expect(page.url()).toContain("/login");
    expect((await request.post("/api/chat", { data: { message: "hi" } })).status()).toBe(401);
    expect((await request.post("/api/chat/stream", { data: { message: "hi" } })).status()).toBe(401);
  });
});

test.describe("Ask AI — dev fixtures", () => {
  test("thread + composer render with a11y affordances", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto("/dev/ask");
    await expect(vis(page.getByText("What's the strongest part of my claim?"))).toBeVisible();

    // role=log aria-live transcript + labeled textarea.
    await expect(page.getByRole("log", { name: "Conversation" })).toBeVisible();
    await expect(page.getByLabel("Ask a question about your claim")).toBeVisible();
    await expect(vis(page.getByRole("button", { name: /Send/ }))).toBeVisible();
    expect(errors, errors.join("\n")).toHaveLength(0);
  });

  test("markdown variant renders formatting + citation chips", async ({ page }) => {
    await page.goto("/dev/ask?v=markdown");
    // Bullet list rendered (markdown), not literal asterisks.
    await expect(vis(page.getByText("A 10% rating applies"))).toBeVisible();
    // CFR citation chip → eCFR, new tab.
    const cfr = page.getByRole("link", { name: /Source: 38 CFR § 4\.71a/ });
    await expect(cfr).toBeVisible();
    expect(await cfr.getAttribute("href")).toContain("ecfr.gov");
    expect(await cfr.getAttribute("target")).toBe("_blank");
    // Doc citation chip → documents page.
    const doc = page.getByRole("link", { name: /Source: March 2019 C&P exam/ });
    await expect(doc).toBeVisible();
    expect(await doc.getAttribute("href")).toContain("/documents");
  });

  test("failed-send variant keeps the text + offers retry/dismiss", async ({ page }) => {
    await page.goto("/dev/ask?v=failed");
    await expect(vis(page.getByText("Can you check my sleep apnea evidence?"))).toBeVisible();
    await expect(vis(page.getByRole("button", { name: /Retry/ }))).toBeVisible();
    await expect(vis(page.getByRole("button", { name: /Dismiss/ }))).toBeVisible();
  });

  test("free persona shows the locked panel + Pro chips, fires no request", async ({ page }) => {
    const calls: string[] = [];
    page.on("request", (r) => {
      if (r.url().includes("/api/chat")) calls.push(r.url());
    });
    await page.goto("/dev/ask?v=free");
    await expect(vis(page.getByText("Ask AI is a Pro feature."))).toBeVisible();
    // Composer is replaced — no question textarea.
    await expect(page.getByLabel("Ask a question about your claim")).toHaveCount(0);
    // Suggestion chips carry a Pro badge and link to upgrade.
    const chip = page.getByRole("link", { name: /strongest part of my claim/ });
    await expect(chip.first()).toBeVisible();
    expect(await chip.first().getAttribute("href")).toContain("/upgrade");
    expect(calls, "no chat request should fire for free users").toHaveLength(0);
  });
});
