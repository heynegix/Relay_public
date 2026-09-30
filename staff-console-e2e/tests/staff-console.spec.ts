import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end browser coverage for the Relay PC Gateway staff/operator console.
 *
 * The server under test is the REAL console (static SPA + real `gatewayModule` routes) launched by
 * playwright.config.ts via the `:pc-gateway:staffConsoleE2eServer` Gradle task, seeded with one
 * IMMEDIATE (life-threatening) and one URGENT rescue request that arrived through the real
 * decrypt/store intake path.
 *
 * The seeded state is shared by the single launched gateway. The "critical SOS" test acknowledges
 * the immediate request (moving it out of UNCONFIRMED), so read-only tests dismiss the critical
 * modal only if this run still has one. Credentials must match the harness bootstrap
 * (see playwright.config.ts env).
 */
const STAFF_USERNAME = "shelter-admin";
const STAFF_PASSWORD = "RelayE2E-Passw0rd!";

async function signIn(page: Page): Promise<void> {
  await page.goto("/");
  // The sign-in dialog gates the console until a local staff account authenticates.
  await expect(page.locator("#setup")).toBeVisible();
  await page.fill("#staffUsername", STAFF_USERNAME);
  await page.fill("#staffPassword", STAFF_PASSWORD);
  await page.click('#setupForm button[type="submit"]');
  // Successful login hides the dialog and stamps the signed-in operator id.
  await expect(page.locator("#setup")).toHaveClass(/hidden/);
  await expect(page.locator("#staffNodeLabel")).toHaveText(STAFF_USERNAME);
}

/** Signs in, waits for the seeded list to render, and clears the critical modal if it is showing. */
async function openConsole(page: Page): Promise<void> {
  await signIn(page);
  await expect(page.locator("#requestList .request-card").first()).toBeVisible();
  const critical = page.locator("#criticalAlert");
  if (await critical.isVisible().catch(() => false)) {
    await page.click("#ackCritical");
    await expect(critical).toHaveClass(/hidden/);
  }
}

test.describe("staff console", () => {
  test("rejects an unknown operator", async ({ page }) => {
    await page.goto("/");
    await page.fill("#staffUsername", STAFF_USERNAME);
    await page.fill("#staffPassword", "definitely-wrong-password");
    await page.click('#setupForm button[type="submit"]');
    // Bad credentials keep the dialog open (the SPA surfaces a validity message and does not enter).
    await expect(page.locator("#setup")).not.toHaveClass(/hidden/);
    await expect(page.locator("#staffNodeLabel")).toHaveText("");
  });

  test("signs in, acknowledges the critical SOS, and advances an operator status", async ({ page }) => {
    await signIn(page);

    // The seeded IMMEDIATE + UNCONFIRMED request must raise the blocking critical-SOS alert.
    const critical = page.locator("#criticalAlert");
    await expect(critical).not.toHaveClass(/hidden/);
    await expect(page.locator("#criticalSummary")).toContainText("3人");

    // Acknowledging claims the request (status -> CONFIRMED) and dismisses the alert.
    await page.click("#ackCritical");
    await expect(critical).toHaveClass(/hidden/);

    // Both seeded requests remain active (non-terminal) and are listed.
    await expect(page.locator("#requestCount")).toHaveText("2");
    await expect(page.locator("#requestList .request-card")).toHaveCount(2);

    // Open the URGENT support request (its card shows the Example Region / Test Site B location) and drive it forward.
    const supportCard = page.locator("#requestList .request-card", { hasText: "Example Region / Test Site B" });
    await expect(supportCard).toBeVisible();
    await supportCard.click();

    const detail = page.locator("#selectedDetail");
    await expect(detail).toContainText("高齢者が孤立");

    // First operator action for an UNCONFIRMED request is "確認して担当開始" (-> CONFIRMED).
    await detail.locator('button[data-status="CONFIRMED"]').click();
    // After the status change the detail re-renders with the next-step action (-> PREPARING).
    await expect(detail.locator('button[data-status="PREPARING"]')).toBeVisible();
  });

  test("shows the signed-in operator and gateway health in settings", async ({ page }) => {
    await openConsole(page);

    await page.click('.tab[data-panel="settings"]');
    await expect(page.locator("#settingsNode")).toHaveText(STAFF_USERNAME);
    // /api/health is served by the same gateway; the SPA renders its status here.
    await expect(page.locator("#healthStatus")).toContainText("Gateway ok");
  });

  test("filters between active and all requests", async ({ page }) => {
    await openConsole(page);

    // Both seeded requests are non-terminal, so the two filters agree here; the control must work.
    await page.click('.filter[data-filter="all"]');
    await expect(page.locator("#requestList .request-card")).toHaveCount(2);
    await page.click('.filter[data-filter="active"]');
    await expect(page.locator("#requestList .request-card")).toHaveCount(2);
  });
});
