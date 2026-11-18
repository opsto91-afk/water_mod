# Orbit Workstation for Android

**Ek app mein Files + Editor + Console + AI chat.** This is an installable workstation, not the older `pocket_bridge.py` copy/paste utility. The AI runs on the phone with [wllama / llama.cpp](https://github.com/ngxson/wllama). It does **not** clone DeepSeek, use a cloud chat login, require an AI API key, or send prompts to a server.

## GitHub se APK download karo

Is folder mein **[Orbit-Workstation.apk](./Orbit-Workstation.apk?raw=true)** par tap karo. Agar GitHub file preview khule, **View raw** ya **Download raw file ⬇** chuno. Poora repo ZIP download karne ya khud build karne ki zaroorat nahi hai.

## Phone par try karo

1. Install [Orbit-Workstation.apk](Orbit-Workstation.apk) (signed debug build, Android 9 or newer). Use a current **Android System WebView**. No Termux, account or PC is needed for the main app.
2. **Editor** opens a real starter project. Change `README.md`, tap **Save**, then reopen it. In **Console**, try `ls` or `wc README.md`. These panels work even without a model.
3. For a real project on your phone: **Files → switch icon next to App storage → Choose device folder**. Android asks you to grant one folder. Use a small test project first. Switch back with **App project**. The default project is private app storage, not your Downloads folder.
4. **Load model → Model download page** opens the publisher's page in your browser. For example, download one Q4_K_M `.gguf` file of [Qwen2.5-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main). Return to **Load model → Choose .gguf file**. The weights are a separate, one-time download subject to the publisher's license; they're not in the APK. Choose the actual file, not the model's web page or a ZIP.
5. In **Assistant** ask, for example, *“Bhai, todo.txt naam ki nayi file bana aur usmein ‘Doodh lana’ likh.”* If the model proposes a supported action, inspect the path/content and tap **Allow once** or **Skip**. The actual result appears in the **console and chat** and goes back to the local model. No copy/paste. For questions about your current draft, toggle **Include open file** before sending.

### What this version can do

| Panel | Implemented behaviour |
| --- | --- |
| Files | Browse/refresh folders, create files and directories. App storage persists locally. The Android system picker also supports writable document trees without an all-files permission. |
| Editor | Read/edit UTF-8 text up to 64 KiB, line numbers, lightweight highlighting, cursor position, Save / Ctrl+S, dirty indicator and change checks before replacing a file. |
| Console | `help`, `pwd`, `ls`, `cd`, `cat`, `wc`, `mkdir`, `touch`, `open`, `clear`. These are project operations, **not an unrestricted shell**. Quote paths containing spaces. |
| Assistant | Real CPU inference from a locally selected GGUF. Streaming replies, stop button, opt-in file context, one-action approvals and feedback. Can list/read/count/create/mkdir, or replace a **unique exact snippet** in an existing file. |

**Limits:** Start with a small instruction-tuned model with a chat template. A model file must be under 1 GiB; fitting on disk does not guarantee sufficient free RAM. Recent 64-bit WebView recommended. Larger models can be slow or fail. This is not the full cloud DeepSeek model. Model quality determines whether it returns a valid proposal; plain text/code is never executed. Select the weights again after restarting. Chat is in memory; saved files persist. A turn stops after at most three approved actions. No delete tool, background agent, arbitrary generated shell, or automatic access to other apps. A cloud-backed Android document provider, if explicitly chosen, can have its own sync behaviour.

### Optional real Termux output

Android **Console** also supports `termux date` and `termux wc "notes/hello.txt"`. These use Termux's official [`RUN_COMMAND` result callback](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent); the latter sends only the selected text to `wc` on stdin, not the folder grant. Neither is an AI shell tool. Requires a compatible Termux (0.109+), coreutils, the user-granted run-command permission and Termux's external-app integration enabled as described in that guide. Only grant this permission to apps you trust. **Skip it entirely** if you only need the built-in workstation.

## Build / verify

The standalone app doesn't change the Water Mod build. Sources: `web/` (UI, inference, storage and action rules), `android/` (local asset WebView, Android folder/file pickers, optional Termux integration). The manifest has **no `INTERNET` permission**. Model and project text are rendered as data; the WebView doesn't navigate to external pages. Runtime JS/WASM and license notices are packaged locally.

- **APK:** With Node 22, JDK 17, Android SDK Platform 35 + Build Tools 35.0.0 and `zip` on Linux/macOS: set `ANDROID_HOME` then run `bash build-android.sh`. Output: `build/Orbit-Workstation.apk`. The script creates a local debug signing key outside the repo. Use the same signing key for in-place updates; an independently signed debug APK will not update the included build. Back up important app-private files before uninstalling.
- **Web preview:** `npm ci && npm run build`, then `python3 -m http.server 4173 --bind 0.0.0.0 --directory build/web`. This serves **static assets only**. Each browser has its own IndexedDB project and local model picker, not access to the server's filesystem; Android folder grants/Termux require the APK.
- **Tests:** `npm test` (rules + storage); after `npm run build`, `CHROME_PATH=/usr/bin/google-chrome npm run test:browser` drives a narrow-screen editor/console session and the model proposal → skip/approve → result flow using a deterministic test double. Set `ORBIT_TEST_GGUF=/path/to/small.gguf` to additionally load the **real bundled engine** and generate tokens. Model files and developer keys are not included.
- **Device checks still needed:** SAF read/write on your provider, model memory/speed on your phone, and the optional Termux permission/result round trip. APK signing and browser tests do not replace those checks.
