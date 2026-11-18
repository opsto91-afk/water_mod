// No endpoints, keys, downloads or remote inference. Only the file chosen by the user.
import { SYSTEM } from "./core.js";
let engine = null,
  wasmURL = null;
export let modelName = "";
export async function unload() {
  const old = engine;
  engine = null;
  modelName = "";
  if (old) await old.exit();
}
export async function load(file) {
  if (
    file.size > 1024 ** 3 ||
    file.size < 16 ||
    (await file.slice(0, 4).text()) !== "GGUF"
  )
    throw Error(
      "Pick a single, compatible GGUF under 1 GiB. Start with a small quantized instruct model.",
    );
  await unload();
  const { Wllama } = await import("./engine/runtime.js");
  if (!wasmURL) {
    const r = await fetch("./engine/runtime.wasm");
    if (!r.ok) throw Error("Bundled inference engine is missing.");
    wasmURL = URL.createObjectURL(
      new Blob([await r.arrayBuffer()], { type: "application/wasm" }),
    );
  }
  const current = new Wllama(
    { "single-thread/wllama.wasm": wasmURL },
    { suppressNativeLog: true },
  );
  try {
    await current.loadModel([file], { n_threads: 1, n_ctx: 2048, n_batch: 64 });
  } catch (e) {
    current.exit().catch(() => {});
    throw Error(
      "Could not load model. Check GGUF compatibility, WebView and free RAM. " +
        (e.message || e),
    );
  }
  engine = current;
  modelName = file.name;
}
export async function reply(history, signal, onText) {
  if (!engine) throw Error("Load a local model first.");
  const turns = [];
  for (const t of history.slice(-8)) {
    if (!turns.length && t.role === "assistant") continue;
    if (turns.at(-1)?.role === t.role)
      turns.at(-1).content += "\n\n" + t.content;
    else turns.push({ ...t });
  }
  let prompt;
  // Fit the actual tokenizer, not a guessed character count. Keep room for the answer.
  for (;;) {
    if (signal.aborted) throw Error("Stopped.");
    prompt = await engine.formatChat(
      [{ role: "system", content: SYSTEM }, ...turns],
      true,
    );
    if (
      (await engine.tokenize(prompt, true)).length <=
      engine.getLoadedContextInfo().n_ctx - 325
    )
      break;
    if (turns.length > 1) {
      turns.shift();
      if (turns[0]?.role === "assistant") turns.shift();
      continue;
    }
    if (!turns.length || turns[0].content.length < 150)
      throw Error("This model has too little usable context.");
    turns[0].content =
      turns[0].content.slice(0, Math.floor(turns[0].content.length * 0.65)) +
      " [excerpt]";
  }
  return engine.createCompletion(prompt, {
    nPredict: 320,
    sampling: { temp: 0.25 },
    useCache: true,
    abortSignal: signal,
    onNewToken: (_t, _p, s) => onText(s),
  });
}
