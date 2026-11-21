# ZaphRE — Portrait AI Reverse-Engineering Launcher for Minecraft Bedrock (Android)

A strictly **Portrait (9:16)** ChatGPT-style Android RE workstation (Ghidra-MCP style) powered by **LeviLauncher-Android v1.7.1**'s native runtime stack (`MC 1.21.80+` through `1.21.130+`, `26.x / 1.26.70+`, and latest Beta/Preview) and **`libZaphkiel.so`**.

## Highlights
- **Separate Portrait Sections (6 Tabs):**
  1. **Chat:** ChatGPT-style autonomous RE loop with live collapsible tool cards, copyable code/signature blocks, and saved session drawer.
  2. **BG Run:** Live background `:mcre` process monitor (PID, RSS MB, `libminecraftpe.so` base VA, `10s / 15s / 30s / 1m / 5m / Custom` watchdog timers, auto-stop on `run() complete`, 1-second hot re-runs, live `zaphkiel_log.txt` & crash tracer), MC APK/`.so` + `libZaphkiel.so` (`.so.txt` / `.levipack`) importer, and full `zaphkiel_config.txt` / `zInput.txt` / `sig_targets.txt` editors with presets.
  3. **Files:** Full RE workspace file manager (`Android/media/org.levimc.zaphr4/zaphkiel/` or shared `Android/media/org.levimc.launcher/kurumi/`) with 100 MB+ chunked dump viewer, in-file grep, editor, import & share.
  4. **CMD:** Built-in Linux toybox shell (`grep`, `awk`, `sed`, `strings`, `xxd`, `find`) + 1-command localhost **Termux Bridge** (`python3`, `rg`, `readelf`, `llvm-objdump`, `clang++`, `radare2`) shared with the AI agent.
  5. **Models:** Download, import, load and manage local GGUF models.
  6. **AI:** All major online APIs (**Google Gemini**, **OpenRouter**, **OpenAI**, **Anthropic Claude**, **DeepSeek**, **Groq**, **xAI Grok**, **Mistral**, custom OpenAI-compatible URL) + **Local AI** (**Termux `llama.cpp`**, **Ollama**, **LM Studio**) with live model fetching and automatic `<tool_call>` XML fallback for small local GGUF models.

## First-Time Phone Setup (60 seconds)
1. Download `ZaphRE-v3.1.0-side-by-side-arm64.apk` from the release and install it. Its package `org.levimc.zaphr4` is separate from older ZaphRE installs; old chats, models and settings do not transfer automatically.
2. Open **BG Run → Setup**:
   - Select your installed Minecraft (or tap **Import APK / .SO** for an isolated copy).
   - Tap **Import `libZaphkiel.so`** (even `libZaphkiel.so.txt` works — `.txt` is stripped automatically).
3. Open **AI**:
   - Pick **Google Gemini** (or OpenRouter / Claude / OpenAI / DeepSeek / Groq / Local `llama.cpp` / Ollama), enter your key (or local URL), tap **Test API** → **Save**.
4. *(Optional)* Open **CMD → Termux Setup** and paste the 1-line bridge command in Termux if you want the AI to also run `python3`, `rg`, `llvm-objdump`, and `readelf`.
5. Go to **Chat** and ask ZaphRE anything — e.g.:
   > *"LiquidBlock ka tick function nikal, pseudo-C samjha, aur cross-version UNIQUE signature generate kar ke BG MC band kar de."*

### Experimental built-in local inference (v2)
The Models tab contains a CPU-only GGUF runtime based on the pinned upstream llama.cpp Android example (MIT license; see `LLAMA_CPP_LICENSE`). Download a listed GGUF or import one from Android's document picker, then tap Load. Inference runs inside the app; no Termux, external server, or API key is needed. Models consume substantial storage and RAM and are not bundled in the APK. Local RE tool calls use a text-based tool-call format: model support varies and results must be checked. Model load and background Minecraft together may exceed available memory. Stop cancels the active network request and stops local generation between generated tokens; a native prefill operation or other blocking tool may take longer to stop. Device compatibility and performance need testing.

### v2.1 local model library
The Models tab includes a small curated coding/reasoning catalogue, a direct HTTPS GGUF URL field, and Android document-picker import. Downloads continue while navigating within the app but not after the app process is stopped; cancelled or interrupted downloads restart from the beginning. Load a downloaded model to select the built-in CPU provider automatically. Models are stored in app-private storage and deleted when the app is uninstalled, so keep an external backup. CPU threads, context window and output limit are configurable. Local generation streams partial text and Stop requests a CPU inference abort; blocking tools and remote services may take longer to stop. Local model tool calling uses an XML fallback and depends heavily on model capabilities; validate all outputs and review autonomous commands. GPU/NPU and Fluent's entire model catalogue are not included.

### Side-by-side package
Version 3.1.0 uses the separate Android package `org.levimc.zaphr4` and a separate 61-byte Zaphkiel workspace path. It can be installed without uninstalling an earlier `org.levimc.zaphre` build. App-private chats, model files, settings and imports are not migrated automatically. GitHub Actions uses a debug signing key on an ephemeral runner, so later builds may not be able to update an installed APK; back up important data before uninstalling anything.

### Offline troubleshooting (development changes)
The built-in model gets compact tool schemas to reduce its system-prompt context cost; actual available space depends on the model tokenizer, configured context, custom instructions and output budget. Native system-prefill failures and context overflow are reported separately. Load or reload a model after changing context. Model download status and cancellation are also displayed near the catalogue. Downloads need internet, enough free app storage and a direct public GGUF URL; offline inference itself does not require an API key. These changes require testing on a device and do not guarantee all models or tool calls will work.

### Optional web search (v3)
In AI settings, web search is off by default. Enable it to let the agent send search queries to DuckDuckGo for links and snippets; internet is required even with an offline model. Avoid private queries. The service can block requests or change its page format; results are untrusted, and tool support depends on the model. Search is not a browser and does not fetch full pages.

### Chat export and findings (v3.1)
Use the download icon in Chat to export the current completed conversation to a location selected in the Android document picker. The text includes messages and available tool outputs, which can contain private information; review before sharing. The app does not include API settings in the export, though any secrets typed in a chat remain in the chat. Saved history may truncate tool outputs to 4000 characters; keep original workspace evidence too. Use Copy under a reply to copy it, or Save finding to save that reply and its available tool steps as a separate text file in Files → notes. These are user-selected notes, not verified facts. Export notes using Files sharing and keep external backups. Version 3.1 has a separate Android package due to independently signed CI builds; data, models, workspace and chats from previous packages are not migrated automatically. Do not uninstall old versions until data is backed up.

### Experimental additional API keys
Online provider settings allow additional keys and an opt-in switch on HTTP 429. Keys are associated with the current provider and URL; each available key is attempted at most once per request. Cooldown deadlines are stored on-device and use the Retry-After header (seconds or HTTP date), or 60 seconds when absent. In auto mode the current request waits and retries while the app process remains active. This is not persistent background work and does not resume a stopped agent. Shared account/project quota may prevent switching from helping. Additional keys are encrypted with a device-local Android Keystore key; the original primary key still uses the existing private preferences storage. Keystore-encrypted keys do not automatically migrate between package IDs or devices. Exported chats can contain text the user entered, including secrets.

### Optional backups and text attachments
Files → Choose backup folder lets Android grant access to a folder. After a completed chat the app writes a new text backup there; Save finding writes a separate file there when selected, otherwise to workspace notes. Existing workspace is not relocated, and each backup is a new file. Check the destination and retain backups separately. Folder permissions can be revoked, and exports may contain private chat content. Tool outputs in saved history may have been truncated.

Chat's attachment picker currently supports one UTF-8 text/code/JSON file up to 64 KiB. The text becomes part of the chat and is sent to the selected provider, so it can affect token usage. PDF, images, video, binary files, large files and attachment-specific multimodal APIs are not supported by this reader.

### Workspace editing
Workspace list supports rename and asks before delete. The text editor now loads the complete UTF-8 file (up to 256 KiB) instead of editing the paginated/clipped viewer output; save refuses to overwrite if the file changed since opening. Folder-selected backups are not part of this workspace browser. AI fs_write requests require approval, but shell/Termux and other tools can still modify workspace files and are not covered by that prompt.

Ask-before-switch mode prompts in Chat before using an additional key after a rate limit; declining stops the retry. Automatic mode does not prompt. This applies only to an active request, not to process-death recovery or background job scheduling.

The AI can list and read bounded UTF-8 files in the selected backup folder, and can request permission to create a new text file or directory there. The parent directory must exist. Text files can also be replaced or deleted with approval and an expected SHA-256; recovery copies are kept in the workspace recovery folder. Provider writes are not atomic, so concurrent changes remain possible. Approval applies to these selected-folder tools only; shell and other existing tools have their own capabilities and are not covered by this prompt.

Named OpenAI-compatible profiles can be created and renamed in AI settings. Save the endpoint, model and credentials before switching; profiles can also be selected in Chat. The optional GitHub tool is off by default and requires repository owner, name, branch and a credential entered locally in the app. It can propose creation or SHA-checked updates of UTF-8 text files (up to 64 KiB), and requires confirmation for each commit. It is not a general Git client, OAuth sign-in, or repository browser. An already submitted network request may finish even if the switch is later turned off. Other tools, including shell/Termux, are separate and are not controlled by this toggle.

Gemini native can receive one PDF, image or video as inline media (up to 12 MiB; video metadata must show at most 60 seconds). The selected model/provider must support the format; large videos may exceed the size limit even when short. Media bytes are retained only during the current agent turn, not stored in chat history, so reattach after an interruption. Scanned PDFs require a capable vision model. OpenAI-compatible providers can receive supported images; Anthropic can receive supported images and PDFs. Model/provider support varies; built-in local inference does not accept media.

Chat files use atomic replacement. Agent turns run in a foreground service and save private restart checkpoints. If Android restarts a killed service, safe non-media checkpoints can resume the model loop; tool batches interrupted mid-execution are marked uncertain and require review rather than replay. A final result checkpoint is restored without re-running tools. Android force-stop, task-manager stop, background limits, local-model unload, revoked permissions, changed settings and service timeouts can prevent automatic recovery; Chat offers manual recovery when a checkpoint is available.

Chat drawer supports chat-only JSON archives and imports copies of supported individual session JSON files, including legacy-format files if the user already has an accessible copy. It does not access the separate v3 app's private storage or transfer API keys, and it does not overwrite the source. Keep v3 installed. Imports create new chat IDs; an interrupted import may have saved some chats already.

Pause/resume in Chat takes effect between model requests and tool executions; an in-flight request or tool can finish before pausing. Pause state is persisted and must be resumed manually after process death. The Stop control cancels the active turn.

When a saved checkpoint contains a tool that was running at interruption, the next turn requires approval for each tool call. A running tool may have completed its side effects even if no result was recorded; check before approving a repeat. Continue starts a new model turn using saved, potentially truncated history, not an exact automatic job resume.

Files → Browse opens the selected document tree separately from the RE workspace. It supports browsing, creating and renaming entries, and bounded UTF-8 file editing/deletion with confirmation and recovery copies. The browser does not delete folders or edit binary files; changing the selected tree closes an open editor.

The app can import an explicitly selected copy of v3's zaphre_ai_settings.xml into unconfigured provider slots, without overwriting existing provider settings. It cannot directly read another package's private storage or obtain this file itself. Such a copy contains credentials and should be kept private; retain v3 until a safe migration is confirmed.
