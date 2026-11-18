import * as local from "./brain.js";
import { callHost, native } from "./storage.js";
import { modelSpec } from "./session.js";
export async function ready(spec) {
  modelSpec(spec.provider, spec.model);
  if (spec.provider === "local") {
    if (!local.modelName) throw Error("Load a GGUF in Models first.");
    return;
  }
  if (!native)
    throw Error(
      "API inference is available in the Android APK, not the browser preview.",
    );
  if (!(await callHost("keyStatus", { provider: spec.provider })))
    throw Error("Set the " + spec.provider + " session key in Models first.");
}
export function cloudTurns(messages) {
  const r = [];
  for (const m of messages.slice(-8)) {
    if (!r.length && m.role !== "user") continue;
    const content = m.content.slice(0, messages.length === 1 ? 48000 : 18000);
    if (r.at(-1)?.role === m.role) r.at(-1).content += "\n\n" + content;
    else r.push({ role: m.role, content });
  }
  while (r.length > 1 && r.reduce((n, t) => n + t.content.length, 0) > 42000) {
    r.shift();
    if (r[0]?.role === "assistant") r.shift();
  }
  return r;
}
export async function infer(
  spec,
  turns,
  system,
  budget,
  signal,
  onText = () => {},
) {
  if (signal.aborted) throw Error("Stopped.");
  if (spec.provider === "local")
    return local.reply(turns, signal, onText, { system, budget });
  return callHost(
    "infer",
    { ...spec, turns: cloudTurns(turns), system, budget },
    signal,
  );
}
