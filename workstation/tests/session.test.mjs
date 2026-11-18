import test from "node:test";
import assert from "node:assert/strict";
import {
  ApprovalBudget,
  COUNTS,
  scopeDisk,
  runTeam,
  workflow,
  modelSpec,
  commandLine,
  inScope,
} from "../web/session.js";
import { proposal, runAction } from "../web/core.js";
const provider = (role, p = "local") => ({
  role,
  provider: p,
  model: p === "local" ? "" : "test-model",
});
test("read allowances count exactly, never cover writes, Termux or a cloud model", () => {
  const g = new ApprovalBudget();
  for (const n of COUNTS) {
    g.set(String(n));
    for (const op of ["create", "edit", "mkdir", "termux_wc", "termux_date"])
      assert(!g.consume(op, "local"));
    assert(!g.consume("read", "claude"));
    assert.equal(g.remaining, n);
    for (let i = 0; i < n; i++) assert(g.consume("list", "local"));
    assert(!g.consume("read", "local"));
  }
  g.set("all");
  for (let i = 0; i < 80; i++) assert(g.consume("count", "local"));
  g.reset();
  assert(!g.consume("read", "local"));
  assert.throws(() => g.set(-1));
});
test("one-file capability never calls backing store for a sibling or parent", async () => {
  const calls = [];
  const d = {
    name: "project",
    read: async (p) => {
      calls.push(p);
      return "x";
    },
    save: async (p) => calls.push(p),
  };
  const one = scopeDisk(d, { file: "notes/todo.txt", write: true });
  assert.deepEqual(await one.list(""), [{ kind: "file", name: "todo.txt" }]);
  assert.equal(await one.read("todo.txt"), "x");
  await one.save("todo.txt", "y", "x");
  await assert.rejects(one.read("other"));
  await assert.rejects(one.list("notes"));
  await assert.rejects(one.read("../todo.txt"));
  await assert.rejects(one.create("other", "x"));
  await assert.rejects(one.mkdir("notes"));
  const ro = scopeDisk(one, { write: false });
  await assert.rejects(
    scopeDisk(ro, { write: true }).save("todo.txt", "new", "x"),
  );
  assert.deepEqual(calls, ["notes/todo.txt", "notes/todo.txt"]);
});
test("Termux tools receive only fixed names and selected text", async () => {
  const a = proposal('{"action":"termux_wc","path":"notes/a"}');
  assert.equal(commandLine(a), 'termux wc "notes/a"');
  const calls = [];
  await runAction({ read: () => "Hi\n" }, a, async (x) => calls.push(x));
  await runAction({}, proposal('{"action":"termux_date"}'), async (x) =>
    calls.push(x),
  );
  assert.deepEqual(calls, [{ tool: "wc", text: "Hi\n" }, { tool: "date" }]);
  assert.throws(() => proposal('{"action":"termux_date","args":["anything"]}'));
});
test("five concurrent API workers produce exactly one manager call", async () => {
  const plan = workflow(
    Array.from({ length: 5 }, (_, i) => provider("w" + i, "deepseek")),
    provider("lead", "claude"),
  );
  let active = 0,
    peak = 0;
  const order = [];
  const output = await runTeam(
    plan,
    "BRIEF",
    async (a, _system, msg) => {
      order.push(a.role);
      if (a.role === "lead") {
        assert.equal(active, 0);
        assert(msg.includes("draft4"));
        return "synthesis";
      }
      active++;
      peak = Math.max(peak, active);
      await new Promise((r) => setTimeout(r, 6));
      active--;
      return "draft" + a.role.slice(1);
    },
    new AbortController().signal,
    () => {},
    "",
    async () => true,
  );
  assert.equal(output, "synthesis");
  assert.equal(peak, 5);
  assert.deepEqual(order, ["w0", "w1", "w2", "w3", "w4", "lead"]);
});
test("local workers queue, failed report is marked, cancellation suppresses manager", async () => {
  const plan = workflow([provider("a"), provider("b")], provider("manager"));
  let n = 0,
    peak = 0;
  const statuses = [];
  await runTeam(
    plan,
    "task",
    async (a, _s, msg) => {
      n++;
      peak = Math.max(n, peak);
      await Promise.resolve();
      n--;
      if (a.role === "a") throw Error("unavailable");
      if (a.role === "manager") assert(msg.includes("[not available]"));
      return "ok";
    },
    new AbortController().signal,
    (_i, s) => statuses.push(s),
  );
  assert.equal(peak, 1);
  assert(statuses.includes("Failed"));
  const c = new AbortController(),
    calls = [];
  await assert.rejects(
    runTeam(
      plan,
      "brief",
      async (a) => {
        calls.push(a.role);
        c.abort();
        return "stopped";
      },
      c.signal,
      () => {},
    ),
  );
  assert.deepEqual(calls, ["a"]);
  assert.throws(() => workflow([], provider("x")));
  assert.throws(() => modelSpec("any", "abc"));
});

test("eligibility distinguishes the document from its parent and guards writes", () => {
  const d = { onlyFile: "a.md", writable: false };
  for (const [action, path] of [
    ["read", "../a.md"],
    ["read", "nested/a.md"],
    ["list", "a.md"],
    ["create", "b.md"],
    ["edit", "a.md"],
  ])
    assert(!inScope({ action, path }, d));
  assert(inScope({ action: "list", path: "" }, d));
  assert(inScope({ action: "read", path: "a.md" }, d));
});
test("optional planning is one extra manager call, delivered to each worker, not an executed instruction", async () => {
  const order = [];
  const plan = workflow(
    [provider("writer", "claude")],
    provider("lead", "deepseek"),
    true,
  );
  await runTeam(
    plan,
    "brief",
    async (a, sys, msg) => {
      order.push(a.role);
      if (order.length === 1) return "write an outline";
      assert(msg.includes("write an outline"));
      return "draft";
    },
    new AbortController().signal,
    () => {},
    "",
    async () => true,
  );
  assert.deepEqual(order, ["lead", "writer", "lead"]);
});

test("denied cross-provider synthesis never sends worker output", async () => {
  const calls = [];
  const plan = workflow(
    [provider("writer", "deepseek")],
    provider("lead", "claude"),
  );
  await assert.rejects(
    runTeam(
      plan,
      "brief",
      async (a) => {
        calls.push(a.role);
        return "report";
      },
      new AbortController().signal,
      () => {},
      "",
      async (agents, text) => {
        assert.deepEqual(agents, [plan.manager]);
        assert(text.includes("report"));
        return false;
      },
    ),
    /kept on device/,
  );
  assert.deepEqual(calls, ["writer"]);
});
