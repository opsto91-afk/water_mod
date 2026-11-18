import { cpSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
process.chdir(fileURLToPath(new URL("../", import.meta.url)));
const out = "build/web",
  lib = "node_modules/@wllama/wllama";
rmSync(out, { recursive: true, force: true });
cpSync("web", out, { recursive: true });
mkdirSync(`${out}/engine`);
cpSync(`${lib}/esm/index.js`, `${out}/engine/runtime.js`);
cpSync(`${lib}/esm/single-thread/wllama.wasm`, `${out}/engine/runtime.wasm`);
cpSync(`${lib}/LICENCE`, `${out}/engine/WLLAMA-LICENSE.txt`);
cpSync("notices/LLAMA-LICENSE.txt", `${out}/engine/LLAMA-LICENSE.txt`);
cpSync(
  "node_modules/@huggingface/jinja/LICENSE",
  `${out}/engine/JINJA-LICENSE.txt`,
);
writeFileSync(
  `${out}/engine/version.txt`,
  "@wllama/wllama 2.4.0 · single-thread CPU · local GGUF files only\n",
);
console.log("Built offline workstation assets in " + out);
