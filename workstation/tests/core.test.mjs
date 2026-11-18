import test from "node:test";
import assert from "node:assert/strict";
import "fake-indexeddb/auto";
import { localDisk } from "../web/storage.js";
import {
  pathOf,
  proposal,
  runAction,
  replaceOnce,
  textOf,
  counts,
} from "../web/core.js";

test("explicit tool schema, fenced JSON and harmless prose", () => {
  assert.equal(proposal("Try ls to see the files."), null);
  assert.equal(proposal("```js\nconsole.log(1)\n```"), null);
  assert.deepEqual(proposal('```json\n{"action":"list","path":"."}\n```'), {
    action: "list",
    path: "",
  });
  for (const s of [
    '{"action":"shell","command":"pwd"}',
    '{"action":"read","path":"a","extra":1}',
    '{"action":"edit","path":"a","before":"","after":"x"}',
  ])
    assert.throws(() => proposal(s));
});
test("relative visible paths and bounded text", () => {
  for (const p of [
    "/etc",
    "../x",
    "a/./b",
    "a//b",
    "a/.hidden",
    "x\\y",
    "a\u202eb",
  ])
    assert.throws(() => pathOf(p));
  assert.equal(pathOf("notes/आज.txt"), "notes/आज.txt");
  assert.throws(() => textOf("क".repeat(23000)));
  assert.throws(() => textOf("a\0b"));
  assert.equal(counts("hi bhai\n"), "1 lines  ·  2 words  ·  8 bytes");
});
test("only one exact original snippet can be edited; replacement is literal", () => {
  assert.throws(() => replaceOnce("aaaa", "aa", "x"));
  assert.throws(() => replaceOnce("one", "two", "x"));
  assert.equal(replaceOnce("my note", "note", "$& done"), "my $& done");
});
test("real IndexedDB create, list, conflict-safe edit and reopen", async () => {
  const d = await localDisk();
  await runAction(d, { action: "mkdir", path: "qa" });
  await runAction(d, {
    action: "create",
    path: "qa/greeting.md",
    text: "namaste\n",
  });
  await assert.rejects(d.create("qa/greeting.md", "overwrite"));
  await assert.rejects(d.create("missing/file", "hi"));
  assert.equal(
    await runAction(d, { action: "list", path: "qa" }),
    "greeting.md",
  );
  await runAction(d, {
    action: "edit",
    path: "qa/greeting.md",
    before: "namaste",
    after: "hello",
  });
  await assert.rejects(d.save("qa/greeting.md", "stale version", "namaste\n"));
  assert.equal(await (await localDisk()).read("qa/greeting.md"), "hello\n");
  await assert.rejects(d.read("../private"));
  await assert.rejects(d.list("qa/greeting.md"));
});
