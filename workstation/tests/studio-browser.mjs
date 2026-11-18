import assert from "node:assert/strict";
// Simulate ONLY the Android/native boundary: real UI, IDB, policy, routing and orchestration.
export async function studioSmoke(browser, url) {
  const p = await browser.newPage({ viewport: { width: 1350, height: 820 } });
  p.setDefaultTimeout(4500);
  const errors = [];
  p.on("pageerror", (e) => errors.push(e.message));
  await p.addInitScript(() => {
    window.fixture = {
      calls: [],
      sent: [],
      terminal: [],
      picks: [],
      meta: null,
      keys: {},
      inflight: 0,
      peak: 0,
    };
    const f = window.fixture;
    window.OrbitHost = {
      request: async (raw) => {
        const q = JSON.parse(raw);
        f.calls.push(q.op);
        let value = true;
        if (q.op === "current") value = f.meta;
        else if (q.op === "terminal") {
          f.terminal.push(q);
          value = q.capture
            ? "Termux exit: 0\nEND OF CLI FIXTURE"
            : "Termux exit: 0\nfixture";
        } else if (q.op === "pick") {
          f.picks.push(q);
          f.meta = {
            name: q.single ? "spec.txt" : "Device project",
            single: q.single,
            write: q.write,
            grant: "token-" + f.picks.length,
          };
          value = f.meta;
        } else if (q.op === "forget") {
          f.meta = null;
        } else if (["list", "read", "save"].includes(q.op)) {
          if (q.grant !== f.meta?.grant) throw Error("stale fixture grant");
          value =
            q.op === "list"
              ? [
                  {
                    kind: "file",
                    name: f.meta.single ? "spec.txt" : "other.txt",
                  },
                ]
              : "Only the picked text";
        } else if (q.op === "keyStatus") value = !!f.keys[q.provider];
        else if (q.op === "setKey") {
          f.keys[q.provider] = true;
        } else if (q.op === "infer") {
          f.sent.push(q);
          f.inflight++;
          f.peak = Math.max(f.peak, f.inflight);
          await new Promise((r) => setTimeout(r, 20));
          f.inflight--;
          value = q.system.includes("managing a project team")
            ? "Team synthesis fixture"
            : q.system.includes("in a project team")
              ? "Report from " + q.model
              : JSON.stringify({ action: "read", path: "README.md" });
        }
        queueMicrotask(() =>
          window.orbitReply(JSON.stringify({ id: q.id, ok: true, value })),
        );
      },
    };
  });
  await p.goto(url);
  await p.locator("#code:not([disabled])").waitFor();
  await p.locator('.tree-entry[title="notes"]').click();
  await p.locator('.tree-entry[title="notes/ideas.md"]').click();
  await p.locator("#agentAccess").click();
  await p.getByLabel("Access level").selectOption("r");
  await p.getByRole("button", { name: "Limit to open file" }).click();
  await p.locator("#code[readonly]").waitFor();
  assert.equal(await p.locator(".tree-entry").count(), 1);
  assert(await p.locator("#newFile").isDisabled());
  await p.reload();
  await p.locator("#code[readonly]:not([disabled])").waitFor();
  assert.equal(await p.locator("#editorPath").textContent(), "ideas.md");
  await p.locator("#rules").click();
  await p.getByLabel("Read allowance").selectOption("2");
  await p.getByRole("button", { name: "Apply rules" }).click();
  for (let i = 0; i < 2; i++) {
    await p.locator("#cmd").fill("cat ideas.md");
    await p.locator("#cmd").press("Enter");
    await p
      .locator("#scopeStatus")
      .filter({ hasText: i ? "Ask every time" : "Next 1" })
      .waitFor();
  }
  await p.locator("#cmd").fill("cat ideas.md");
  await p.locator("#cmd").press("Enter");
  await p.locator("#reviewDeny").click();
  await p.locator("#log").getByText("Denied · nothing ran.").waitFor();
  await p.locator("#grantScope").click();
  await p.getByLabel("Access level").selectOption("rw");
  await p.getByRole("button", { name: "Use app project" }).click();
  await p.locator("#code:not([readonly]):not([disabled])").waitFor();
  await p.locator("#modelBtn").click();
  await p.getByLabel("Provider", { exact: true }).selectOption("deepseek");
  await p.getByLabel("Model ID from your account").fill("fixture-model");
  await p.getByRole("button", { name: "Set session key" }).click();
  await p.getByText("Session key set", { exact: true }).waitFor();
  await p.getByRole("button", { name: "Use in Solo" }).click();
  await p.locator("#message").fill("Read my file");
  await p.locator("#send").click();
  await p.locator("#reviewDeny").click();
  assert.equal(await p.evaluate(() => fixture.sent.length), 0);
  await p.locator("#send").click();
  await p.locator("#reviewAllow").click();
  await p.getByRole("button", { name: "Allow once", exact: true }).click();
  await p
    .locator("#reviewPreview")
    .filter({ hasText: "# Pocket project" })
    .waitFor();
  await p.locator("#reviewDeny").click();
  await p.getByText("Kept on device", { exact: true }).waitFor();
  assert.equal(await p.evaluate(() => fixture.sent.length), 1);
  await p.locator("#team").click();
  await p.getByLabel("Worker count").selectOption("5");
  await p.getByLabel("Workflow name", { exact: true }).fill("Five writers");
  await p.getByRole("button", { name: "Save workflow" }).click();
  await p.getByLabel("Team brief").fill("Review a small project");
  await p.getByRole("button", { name: "Review run" }).click();
  await p.locator("#reviewDetail").filter({ hasText: "6 calls" }).waitFor();
  await p.locator("#reviewAllow").click();
  await p
    .locator("#reviewTitle")
    .getByText("Send worker reports to the manager?", { exact: true })
    .waitFor();
  assert.equal(await p.evaluate(() => fixture.sent.length), 6);
  assert(
    (await p.locator("#reviewPreview").innerText()).includes(
      "Report from fixture-model",
    ),
  );
  await p.locator("#reviewAllow").click();
  await p.getByText("Team synthesis fixture", { exact: true }).last().waitFor();
  const state = await p.evaluate(() => ({
    n: fixture.sent.length,
    peak: fixture.peak,
    all: fixture.sent.slice(1).map((q) => q.turns),
  }));
  assert.equal(state.n, 7);
  assert.equal(state.peak, 5);
  assert(!JSON.stringify(state.all).includes("# Pocket project"));
  assert.equal(await p.locator(".team-output").count(), 6);
  assert.deepEqual(errors, []);
  await p.locator("#grantScope").click();
  await p.getByRole("button", { name: "Choose folder", exact: true }).click();
  await p.locator('.tree-entry[title="other.txt"]').waitFor();
  await p.locator("#grantScope").click();
  await p.getByLabel("Access level").selectOption("r");
  await p.getByRole("button", { name: "Choose one file", exact: true }).click();
  await p
    .locator("#editorPath")
    .getByText("spec.txt", { exact: true })
    .waitFor();
  assert(await p.locator("#code").evaluate((e) => e.readOnly));
  assert.equal(await p.locator(".tree-entry").count(), 1);
  assert.deepEqual(
    await p.evaluate(() =>
      fixture.picks.map(({ single, write }) => [single, write]),
    ),
    [
      [false, true],
      [true, false],
    ],
  );
  await p.locator("#cliTools").click();
  await p.getByLabel("Installed agy path").selectOption("home");
  await p.getByRole("button", { name: "Check version", exact: true }).click();
  await p.locator("#reviewAllow").click();
  await p
    .locator("#log")
    .getByText("Termux exit: 0", { exact: false })
    .last()
    .waitFor();
  await p.locator("#cliTools").click();
  await p
    .getByLabel("Task for agy (optional)", { exact: true })
    .fill("Write a plan");
  await p
    .getByLabel("Return the end of the terminal transcript", { exact: false })
    .check();
  await p
    .getByRole("button", { name: "Review CLI session", exact: true })
    .click();
  await p.locator("#reviewDeny").click();
  assert.equal(await p.evaluate(() => fixture.terminal.length), 1);
  await p
    .getByRole("button", { name: "Review CLI session", exact: true })
    .click();
  await p.locator("#reviewAllow").click();
  await p
    .locator("#transcript")
    .getByText(/END OF CLI FIXTURE/)
    .waitFor();
  const ti = await p.evaluate(() => fixture.terminal[1]);
  assert.equal(ti.brief, "Write a plan");
  assert.equal(ti.slot, "home");
  assert.equal(ti.capture, true);
  assert.equal(ti.text, undefined);
  assert.equal(await p.evaluate(() => fixture.sent.length), 7);
  assert.deepEqual(errors, []);
  await p.close();
  console.log(
    "Studio: single-file read-only scope/reopen, exact budgets/deny, cloud transfer consent and five concurrent workers + manager: OK (native/provider fixtures).",
  );
}
