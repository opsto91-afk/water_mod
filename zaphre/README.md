# ZaphRE — Portrait AI Reverse-Engineering Launcher for Minecraft Bedrock (Android)

A strictly **Portrait (9:16)** ChatGPT-style Android RE workstation (Ghidra-MCP style) powered by **LeviLauncher-Android v1.7.1**'s native runtime stack (`MC 1.21.80+` through `1.21.130+`, `26.x / 1.26.70+`, and latest Beta/Preview) and **`libZaphkiel.so`**.

## Highlights
- **Separate Portrait Sections (5 Tabs):**
  1. **Chat:** ChatGPT-style autonomous RE loop with live collapsible tool cards, copyable code/signature blocks, and saved session drawer.
  2. **BG Run:** Live background `:mcre` process monitor (PID, RSS MB, `libminecraftpe.so` base VA, `10s / 15s / 30s / 1m / 5m / Custom` watchdog timers, auto-stop on `run() complete`, 1-second hot re-runs, live `zaphkiel_log.txt` & crash tracer), MC APK/`.so` + `libZaphkiel.so` (`.so.txt` / `.levipack`) importer, and full `zaphkiel_config.txt` / `zInput.txt` / `sig_targets.txt` editors with presets.
  3. **Files:** Full RE workspace file manager (`Android/media/org.levimc.zaphre/zaphkiel/` or shared `Android/media/org.levimc.launcher/kurumi/`) with 100 MB+ chunked dump viewer, in-file grep, editor, import & share.
  4. **CMD:** Built-in Linux toybox shell (`grep`, `awk`, `sed`, `strings`, `xxd`, `find`) + 1-command localhost **Termux Bridge** (`python3`, `rg`, `readelf`, `llvm-objdump`, `clang++`, `radare2`) shared with the AI agent.
  5. **AI:** All major online APIs (**Google Gemini**, **OpenRouter**, **OpenAI**, **Anthropic Claude**, **DeepSeek**, **Groq**, **xAI Grok**, **Mistral**, custom OpenAI-compatible URL) + **Local AI** (**Termux `llama.cpp`**, **Ollama**, **LM Studio**) with live model fetching and automatic `<tool_call>` XML fallback for small local GGUF models.

## First-Time Phone Setup (60 seconds)
1. Download `ZaphRE-v1.0.0-arm64.apk` from GitHub Actions (**ZaphRE APK Build**) and install it (installs cleanly alongside official LeviLauncher).
2. Open **BG Run → Setup**:
   - Select your installed Minecraft (or tap **Import APK / .SO** for an isolated copy).
   - Tap **Import `libZaphkiel.so`** (even `libZaphkiel.so.txt` works — `.txt` is stripped automatically).
3. Open **AI**:
   - Pick **Google Gemini** (or OpenRouter / Claude / OpenAI / DeepSeek / Groq / Local `llama.cpp` / Ollama), enter your key (or local URL), tap **Test API** → **Save**.
4. *(Optional)* Open **CMD → Termux Setup** and paste the 1-line bridge command in Termux if you want the AI to also run `python3`, `rg`, `llvm-objdump`, and `readelf`.
5. Go to **Chat** and ask ZaphRE anything — e.g.:
   > *"LiquidBlock ka tick function nikal, pseudo-C samjha, aur cross-version UNIQUE signature generate kar ke BG MC band kar de."*

### Experimental built-in local inference (v2)
The AI tab contains a CPU-only GGUF runtime based on the pinned upstream llama.cpp Android example (MIT license; see `LLAMA_CPP_LICENSE`). Download a listed GGUF or import one from Android's document picker, then tap Load. Inference runs inside the app; no Termux, external server, or API key is needed. Models consume substantial storage and RAM and are not bundled in the APK. Local RE tool calls use a text-based tool-call format: model support varies and results must be checked. Model load and background Minecraft together may exceed available memory. Stop cancels the active network request and stops local generation between generated tokens; a native prefill operation or other blocking tool may take longer to stop. Device compatibility and performance need testing.
