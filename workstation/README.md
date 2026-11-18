# Orbit Workstation 0.2 · Android

**[Download the signed APK](./Orbit-Workstation.apk?raw=true)** · [SHA-256 checksum](./SHA256SUMS) · [UI source](./web/) · [Android source](./android/)

Ek app mein project files, editor, command review aur AI chat. **Android 9+**, current Android System WebView recommended. GitHub agar file preview dikhaye to **View raw / Download raw file** chuno. This build uses the same package and signing certificate as the repo’s 0.1.0: install over it, **don’t uninstall first**. An independently signed build may not update it. The app is separate from the Minecraft mod and Pocket Bridge.

## First run

1. **Assistant → Access → Choose folder** for a project, or **Choose one file** and navigate to the exact file, even several folders deep. Choose **Read only** or **Read & write** first. For example, selecting `notes/todo.md` as one file exposes just `todo.md` at the workspace root, not the contents of `notes/`. The default *Pocket project* lives in app storage; **Limit to open file** also works there.
2. **Models → On-device GGUF → Choose .gguf file** for the no-paid-key route. The local engine is included; model weights are not. **Model page** opens the publisher’s [Qwen2.5-0.5B-Instruct-GGUF page](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main) in your browser; download one small compatible instruction-tuned GGUF (e.g. Q4_K_M). Pick it again after restarting. RAM and WebView limit what will run; the size limit is 1 GiB. The app doesn't pretend this is the full DeepSeek cloud model.
3. Ask *“todo.md padh ke bata kya pending hai”* or *“Is project mein plan.md bana, usme teen steps likh.”* The assistant proposes a command with its target and, for a write, its content/change. **Allow once** runs it in the selected scope; **Deny** runs nothing. Actual results appear in chat and Console. A solo model can continue from the result. Nothing to copy into a second chat. Use **Include open file** to attach an excerpt of the current editor draft.
4. **Rules** sets the read allowance and **Normal / Heavy**. An eligible assistant command also has a repeat selector on the review card. **Allow next 1–10, 20, 40 or 50** includes the current read when chosen on its card; **Allow all local reads** lasts for this session. The counter is shared with the workspace console. Changing scope, solo model or conversation resets it. A write, Termux call, cloud-model command or provider transfer is **never** covered by that allowance.

### What the file grant means

The Android system picker grants one document or one tree. The app retains only the active grant; native code checks the grant and access level as well as the UI. A new grant invalidates old file handles and clears the conversation. No all-files permission or raw device paths. A cloud-synced Android document provider can still sync its own files.

“Full project” means access to visible files and subfolders in the **selected scope**, not a root shell. The editor handles UTF-8 files up to 64 KiB with unsaved-change and overwrite checks. Create is new-only; AI edits replace a unique exact snippet. No delete or arbitrary generated shell. The built-in console understands `help`, `pwd`, `ls`, `cd`, `cat`, `wc`, `mkdir`, `touch`, `open`, `clear`; quote paths with spaces. Editor and Console work without any model.

## Optional Claude and DeepSeek APIs

In **Models**, choose **Claude API** or **DeepSeek API**, enter a model ID available in your own API account, then **Set session key**. The key is entered in an Android password dialog, held in native memory only and used as an authentication header to that provider. **Forget key** clears it. A consumer chat subscription/login is not an API key. Do not put keys in prompts, model IDs, files to be shared, or saved workflow names.

**Use in Solo** changes the chat model; **Set team default** only fills that provider's model ID in the workflow builder. No proxy, cloud fallback or bundled credits. For solo, review the displayed conversation/excerpt before each request and the actual tool result before sending it back. Denying the latter keeps the result on device and out of the provider history. Endpoints are fixed: Anthropic Messages and DeepSeek Chat Completions over HTTPS. Provider use may cost money; check your account limits. The APK requests `INTERNET` for these optional calls; local inference loads from packaged assets and your selected model file.

| Work mode | Solo action limit per message | Output limit per model call |
| --- | ---: | --- |
| Normal | 3 | Local 320 tokens; API 1,024 |
| Heavy | 8 | Local 768 tokens; API 4,096 |

## Build a team workflow

**Assistant → Team**. Choose **1–5 workers** and a separate manager. Give each a role, provider and (for an API) a model ID. For example: Planner, Implementer, Reviewer, Test designer, Writer → Lead editor. Load the local GGUF / set any necessary API keys first. Save a named configuration to reuse it (up to eight); it saves roles and model choices, **not** prompts, files, replies or keys.

- Enter a brief, optionally attach the open-file excerpt, then **Review run**. Check every destination and the maximum API call count before starting.
- Optionally have the manager assign work first. API workers can run concurrently; local roles share **one** loaded model and queue to avoid loading several models into phone RAM. One run makes up to `workers + 1` calls, or `workers + 2` with planning. No automatic retries.
- Expand each worker to see its status and output. Workers draft independently; they cannot execute shell commands or write over one another. The manager reads bounded excerpts, reports missing workers and synthesizes a result. Before new manager assignments or worker reports go to an API, review the actual text and destinations. Deny keeps it local. Stop prevents further calls, not charges for requests already sent.
- The manager may propose one workspace action for individual review. Its actual result stays in Orbit. **Use manager result in Solo** carries the report and decision into follow-up work; it does not make an API call by itself.

## Termux and Antigravity CLI

This is optional and runs on **your Android device**, not a remote Linux server. Use a compatible [Termux](https://github.com/termux/termux-app) (0.109+), grant Orbit its run-command permission, and enable `allow-external-apps=true` in Termux settings per the [official RUN_COMMAND guide](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). Reload the Termux settings after changing them. Only grant this integration to apps you trust.

1. **Console → CLI tools → Test Termux** runs a fixed `date -u`. `termux wc "notes/todo.md"` sends only that scoped file's text to `wc` on stdin. Both can also be proposed by the assistant; approval is required every time.
2. To use Antigravity, install/sign in to the **official CLI in Termux** separately. [Upstream README and installation](https://github.com/google-antigravity/antigravity-cli/blob/fe76a73ca863c109d18738103d12d0f4f4553328/README.md) · [upstream 1.2.15 native Android/Termux release note](https://github.com/google-antigravity/antigravity-cli/blob/fe76a73ca863c109d18738103d12d0f4f4553328/CHANGELOG.md#1215). Orbit does not install an executable or manage your Google login. In Termux, `command -v agy` shows your installation; in **CLI tools** select `$PREFIX/bin/agy` or `$HOME/.local/bin/agy`. If installed elsewhere, make it available at one of those two locations first.
3. **Check version** (`agy --version`), then **List models** (`agy models`) or **Account usage** (`agy -p /usage`). A successful check at that location is required for further CLI actions. These fixed commands return exit status and real output to Orbit. The assistant can propose the version and model queries, not an arbitrary headless CLI job.
4. For a coding session choose **Review CLI session**, optionally enter a task to open in CLI plan mode, and review exactly what will be passed. The interactive TUI opens **in Termux** and uses the CLI’s own account, tools and approval rules, not Orbit's file grant. Orbit passes **no workspace paths, file contents or API keys** automatically. If Android doesn't bring it forward, tap the Termux notification. Enable **Return the end of the terminal transcript** only if you want it back in this chat when you exit `agy`; leave this **off** for login. Captured output is bounded, not a live terminal stream, is not forwarded to a model, and needs the same open Orbit session (up to an hour). Closing/changing the chat discards a late transcript.

## Build and verification

- **APK:** From this folder, with Node 22, JDK 17, Android SDK platform 35, Build Tools 35.0.0 and `zip`: set `ANDROID_HOME` and run `bash build-android.sh`. Output: `build/Orbit-Workstation.apk`. The normal SDK build makes a private local debug key outside the repo. Compare the checked-in binary with `sha256sum -c SHA256SUMS`. Independently signed builds will have a different checksum and signer.
- **Browser preview:** `npm ci && npm run build`, then `python3 -m http.server 4173 --bind 0.0.0.0 --directory build/web`. Static UI, local GGUF and browser-private IndexedDB only; no server-side shell or API proxy. Device document grants, provider keys and Termux require the APK.
- **Tests:** `npm test`; then `npm run build && CHROME_PATH=/path/to/chrome npm run test:browser`. Optionally set `ORBIT_TEST_GGUF=/path/to/model.gguf` to exercise the real bundled WASM engine as well. `JSON_JAR=/path/to/org.json.jar bash tests/native-contract.sh` exercises the actual Java permission policy, CLI argument builder and provider adapters with an in-memory HTTPS transport (JDK 17). No test contacts a paid provider.
- **Verification limits:** Automated editor, denial, scope, counted approvals, team concurrency/transfer consent and CLI transcript tests use deterministic fixtures for the Android/provider boundaries. The packaged engine was also exercised with a small synthetic GGUF; that's not a model-quality benchmark. Signing, packaging and browser tests do **not** establish phone-specific SAF, Termux/`agy`, memory/speed or paid-account compatibility. Try a disposable project before important files.
