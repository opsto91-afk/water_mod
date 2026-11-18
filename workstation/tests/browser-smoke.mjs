// Integration test. A deterministic model stub checks the approval handoff; an optional
// local GGUF separately exercises the REAL bundled WASM engine. Neither is in the APK.
import { chromium } from "playwright-core";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, resolve, sep } from "node:path";
import assert from "node:assert/strict";
const root = resolve("build/web");
const server = createServer(async (q, r) => {
  try {
    const p = resolve(
      root,
      "." +
        new URL(q.url, "http://local").pathname.replace(/\/$/, "/index.html"),
    );
    if (!p.startsWith(root + sep)) throw Error();
    const f = await readFile(p);
    r.setHeader(
      "content-type",
      {
        ".js": "text/javascript",
        ".wasm": "application/wasm",
        ".html": "text/html",
        ".css": "text/css",
        ".svg": "image/svg+xml",
      }[extname(p)] || "text/plain",
    );
    r.end(f);
  } catch {
    r.writeHead(404);
    r.end();
  }
});
await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
const url = "http://127.0.0.1:" + server.address().port;
let browser;
try {
  browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH || "google-chrome",
    args: ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"],
  });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/brain.js", (route) =>
    route.fulfill({
      contentType: "text/javascript",
      body: `export const modelName='QA only';export async function reply(h){if(h.at(-1).content.startsWith('Actual local tool result'))return 'Received the tool result.';return JSON.stringify({action:'create',path:'agent-note.md',text:'Made in the workstation'});}`,
    }),
  );
  await page.goto(url);
  await page.locator("#code:not([disabled])").waitFor();
  await page.locator("#code").fill("# Saved offline");
  await page.locator("#save").click();
  await page.locator("#save[disabled]").waitFor();
  await page.reload();
  await page.locator("#code:not([disabled])").waitFor();
  assert.equal(await page.locator("#code").inputValue(), "# Saved offline");
  await page.locator("[data-tab=assistant]").click();
  await page.locator("#message").fill("Create a note");
  await page.locator("#send").click();
  await page.getByRole("button", { name: "Skip", exact: true }).click();
  assert.equal(
    await page.locator('.tree-entry[title="agent-note.md"]').count(),
    0,
  );
  await page.locator("#message").fill("Create the note now");
  await page.locator("#send").click();
  await page.getByRole("button", { name: "Allow once", exact: true }).click();
  await page.getByText("Received the tool result.", { exact: true }).waitFor();
  await page.locator("[data-tab=files]").click();
  await page.locator('.tree-entry[title="agent-note.md"]').click();
  assert.equal(
    await page.locator("#code").inputValue(),
    "Made in the workstation",
  );
  await page.locator("[data-tab=console]").click();
  await page.locator("#cmd").fill("wc agent-note.md");
  await page.locator("#cmd").press("Enter");
  await page
    .locator("#log")
    .getByText(/4 words/)
    .waitFor();
  console.log(
    "Mobile editor persistence, skip/approve, result feedback, file explorer and console: OK (stub model).",
  );
  if (process.env.ORBIT_TEST_GGUF) {
    await page.unroute("**/brain.js");
    await page.reload();
    await page.locator("[data-tab=assistant]").click();
    await page.locator("#modelBtn").click();
    await page
      .locator("#modelInput")
      .setInputFiles(process.env.ORBIT_TEST_GGUF);
    await page
      .locator("#modelBtnLabel", { hasText: "Model ready" })
      .waitFor({ timeout: 90000 });
    await page.locator("#message").fill("Hi");
    await page.locator("#send").click();
    await page.locator(".msg.assistant pre").waitFor();
    await page
      .locator('#send[aria-label="Send message"]')
      .waitFor({ timeout: 120000 });
    const s = await page.locator(".msg.assistant pre").first().textContent();
    assert(
      s && !s.startsWith("Could not complete:") && !s.startsWith("(No text"),
    );
    console.log("Actual bundled WASM loaded GGUF and generated output: OK.");
  }
  assert.deepEqual(errors, []);
} finally {
  if (browser) await browser.close();
  server.closeAllConnections();
  await new Promise((ok) => server.close(ok));
}
