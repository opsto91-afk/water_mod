import { STARTER, pathOf, parentOf, baseOf, textOf } from "./core.js";
const req = (r) =>
  new Promise((yes, no) => {
    r.onsuccess = () => yes(r.result);
    r.onerror = () => no(r.error);
  });
export async function localDisk() {
  const request = indexedDB.open("orbit-project-v1", 1);
  request.onupgradeneeded = () => {
    const s = request.result.createObjectStore("items", { keyPath: "path" });
    s.createIndex("parent", "parent");
    s.add({ path: "", kind: "dir" });
    for (const item of STARTER) s.add({ ...item, parent: parentOf(item.path) });
  };
  const db = await req(request);
  async function transaction(mode, fn) {
    const tx = db.transaction("items", mode);
    const end = new Promise((yes, no) => {
      tx.oncomplete = yes;
      tx.onabort = () =>
        no(tx.error || Error("Storage transaction cancelled."));
    });
    try {
      const result = await fn(tx.objectStore("items"));
      await end;
      return result;
    } catch (e) {
      try {
        tx.abort();
      } catch {}
      await end.catch(() => {});
      throw e;
    }
  }
  const edit = (p, kind, text, expected) =>
    transaction("readwrite", async (s) => {
      pathOf(p);
      if (kind === "file") textOf(text);
      const dest = await req(s.get(p));
      if (expected === undefined && dest)
        throw Error("A file or folder already exists here.");
      if (
        expected !== undefined &&
        (dest?.kind !== "file" || dest.text !== expected)
      )
        throw Error(
          "File changed since it was opened. Reopen it before saving.",
        );
      if ((await req(s.get(parentOf(p))))?.kind !== "dir")
        throw Error("Parent folder does not exist.");
      await req(
        s.put({
          path: p,
          parent: parentOf(p),
          kind,
          ...(kind === "file" ? { text } : {}),
        }),
      );
    });
  return {
    name: "Pocket project",
    type: "App storage",
    list: (p) =>
      transaction("readonly", async (s) => {
        p = pathOf(p, true);
        if ((await req(s.get(p)))?.kind !== "dir")
          throw Error("Folder not found.");
        const keys = await req(s.index("parent").getAllKeys(p, 251));
        if (keys.length > 250)
          throw Error("Open a smaller folder (maximum 250 entries).");
        return Promise.all(
          keys.map(async (k) => ({
            name: baseOf(k),
            kind: (await req(s.get(k))).kind,
          })),
        );
      }),
    read: (p) =>
      transaction("readonly", async (s) => {
        const e = await req(s.get(pathOf(p)));
        if (e?.kind !== "file") throw Error("Text file not found.");
        return e.text;
      }),
    create: (p, t) => edit(p, "file", t),
    mkdir: (p) => edit(p, "dir"),
    save: (p, t, old) => edit(p, "file", t, old),
  };
}
const pending = new Map();
let seq = 0;
export const native = typeof window !== "undefined" && !!window.OrbitHost;
if (native)
  window.orbitReply = (raw) => {
    const msg = JSON.parse(raw),
      cb = pending.get(msg.id);
    if (!cb) return;
    pending.delete(msg.id);
    clearTimeout(cb.timer);
    msg.ok ? cb.yes(msg.value) : cb.no(Error(msg.error));
  };
export function callHost(op, args = {}) {
  if (!native) return Promise.reject(Error("Available in the Android app."));
  return new Promise((yes, no) => {
    const id = String(++seq),
      timer = setTimeout(
        () => {
          pending.delete(id);
          no(
            Error(
              "Android did not return a result. Check the app and permissions.",
            ),
          );
        },
        op === "pick" ? 180000 : 30000,
      );
    pending.set(id, { yes, no, timer });
    try {
      window.OrbitHost.request(JSON.stringify({ id, op, ...args }));
    } catch (e) {
      clearTimeout(timer);
      pending.delete(id);
      no(e);
    }
  });
}
export function androidDisk(name) {
  return {
    name,
    type: "Device folder",
    list: (p) => callHost("list", { path: p }),
    read: (p) => callHost("read", { path: p }),
    mkdir: (p) => callHost("mkdir", { path: p }),
    create: (p, text) => callHost("create", { path: p, text }),
    save: (p, text, expected) => callHost("save", { path: p, text, expected }),
  };
}
