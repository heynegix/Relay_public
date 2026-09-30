import { defineConfig, devices } from "@playwright/test";
import * as path from "path";
import * as os from "os";
import * as fs from "fs";

/**
 * Playwright config for the Relay staff-console browser E2E.
 *
 * `webServer` launches the REAL PC Gateway operator console through the test-only
 * `:pc-gateway:staffConsoleE2eServer` Gradle task. That task boots the same `gatewayModule`
 * routes + static SPA production serves, bound to loopback (which makes the console
 * management-source allowed), with a bootstrapped admin and seeded rescue requests. All state
 * lives in a throwaway temp directory that is created per run.
 */
const PORT = Number(process.env.RELAY_E2E_PORT || 8099);
const BASE_URL = `http://127.0.0.1:${PORT}`;

// Shared with tests/staff-console.spec.ts (kept identical there; webServer env is not visible to
// the test process, so the spec hardcodes the same credentials).
export const STAFF_USERNAME = "shelter-admin";
export const STAFF_PASSWORD = "RelayE2E-Passw0rd!";

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "relay-staff-e2e-"));
const gradlew = process.platform === "win32" ? "gradlew.bat" : "./gradlew";

export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `${gradlew} :pc-gateway:staffConsoleE2eServer --console=plain`,
    cwd: path.resolve(__dirname, ".."),
    url: `${BASE_URL}/`,
    reuseExistingServer: !process.env.CI,
    timeout: 240_000,
    stdout: "pipe",
    stderr: "pipe",
    env: {
      RELAY_PROFILE: "development",
      RELAY_GATEWAY_HOST: "127.0.0.1",
      RELAY_GATEWAY_PORT: String(PORT),
      RELAY_SHELTER_ID: "example-01",
      RELAY_GATEWAY_ID: "pc-gateway-e2e",
      RELAY_GATEWAY_DB: path.join(tmpRoot, "gateway.db"),
      RELAY_RESCUE_KEY_FILE: path.join(tmpRoot, "rescue-keys.json"),
      RELAY_OFFLINE_MAP_DIR: path.join(tmpRoot, "maps"),
      RELAY_OFFICIAL_INFO_CACHE: path.join(tmpRoot, "official.json"),
      RELAY_E2E_STAFF_USERNAME: STAFF_USERNAME,
      RELAY_E2E_STAFF_PASSWORD: STAFF_PASSWORD,
      // Keep everything on loopback: no LAN discovery beacon during the browser test.
      RELAY_GATEWAY_LAN_DISCOVERY: "false",
    },
  },
});
