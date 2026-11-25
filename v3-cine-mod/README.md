# V3 Cine Mod — LeviLauncher native mod (Minecraft Bedrock, Android arm64)

Ye ek **cinematic mod** hai (movies / web series banane ke liye), replay mod nahi. Naam me "replay" sirf isliye aaya
kyunki idea ReplayCraft / HeroCine mcaddon se aaya. Mod Menu se **V3 Cine Mod** on karo, to game ke upar ImGui ka
video-editor jaisa UI aata hai: presets, keys, segments, transitions, key points aur timeline.

> Is zip ko "handoff" samjho: yahan tak ka kaam complete aur tested hai, **game se camera jodna baaki hai** (neeche).
> Poori kahani aur tumhara idea: `docs/IDEA_AND_HISTORY.md` — aage ka kaam: `docs/NEXT_STEPS.md`.

## Ek nazar me: kya ho gaya, kya baaki

| Cheez | Haal |
|---|---|
| ImGui UI game ke upar (eglSwapBuffers hook, frame capture) | **Phone par chala** (tumhare screenshots) |
| Panels: Editor / Tools / Timeline toggle buttons, divider drag se resize, touch-scroll | Phone par chala (V3 screenshots) |
| Presets (3 slots), Create popup, Add / Update / Delete Key, Save | Phone par chala |
| Segments (N keys = N-1 segments), rangeen strips, 32 ease types, duration drag | Phone par chala |
| Transitions `+` (Instant / Hold / Fade, max 2s), Key Points (pins) | Phone par chala (Hold aur pin dikhe) |
| Touch pass-through (jo touch hamare UI par nahi, wo game ko), `Live` button, flat `.levipack` (V3.1) | **PC test pass, phone par abhi test nahi** |
| Camera engine: timeline se pose(t), ease, fade alpha | PC test pass (122 checks, ASan/UBSan clean) |
| **Game camera ko pose dena (flow)** | **BAAKI** — hook chahiye |
| **Player ki asli position/rotation padhna (keys asli POV se)** | **BAAKI** — hook chahiye |
| Command chalana (`/camera`, `/hud`, fade) | Command strings ready, **executor BAAKI** |

Matlab: UI + timeline + segments + transitions + pins + engine ready hain. Game se jodne ke 3 hook points
`src/GameBridge.cpp` me khaali hain (TODO). Camera abhi isi wajah se game me nahi chalta.

## Build
Is repository mein GitHub Actions ka **Cine Mod Build** workflow use karo: Actions → Cine Mod Build → Run workflow → complete hone par `cinematic_mod-levipack` artifact download karo. Artifact ke andar `cinematic_mod.levipack` LeviLauncher mein import karo. Workflow file repository root mein `.github/workflows/cine-mod.yml` hai; folder ke andar wala purana workflow GitHub Actions khud discover nahi karta.

1. Android NDK (r25+), `ANDROID_NDK_HOME` set karo.
2. `./build.sh` → `dist/cinematic_mod/` aur `dist/cinematic_mod.levipack` (flat zip, manifest root me).
   - Internet chahiye (Preloader SDK GitHub se). Offline: `PRELOADER_SDK_DIR=/path/to/preloader-android ./build.sh`
   - PC nahi hai? Repo GitHub par daalo, `.github/workflows/build.yml` build kar dega.
3. LeviLauncher me `.levipack` import karo. Folder naam `cinematic_mod` hi mod id hai (purane version ko replace karta hai;
   do versions saath mat rakho, dono `eglSwapBuffers` hook karenge).
4. PC par UI test (bina phone): `make -C tests run`

## Kaise use karna hai
- Mod Menu → **V3 Cine Mod** on → menu band karo. Game full screen dikhta hai.
- Top-left `Editor`, top-right `Tools`, bottom `Timeline` buttons panel kholte/band karte hain. `X` = band.
- Panel aur game ke beech ki divider (grip dots) kheencho to size badlega. Panel me khaali jagah drag = scroll.
- `Editor → Presets → + Create New` → naam + category → editor khulta hai.
- `+ Add Key` har baar ek key lagata hai: key 1, key 2 → **Segment 1**; key 3 → Segment 2 ... (mcaddon jaisa).
- Strip tap = segment select (Tools me Duration + Ease). Strip ka end-diamond kheencho = segment ka time.
- Do segments ke beech `+` = transition (Instant / Hold / Fade + seconds).
- `+ Pin` = key point (reminder), label Tools me likho, kheench kar move karo.
- Moon = night vision, sun = full brightness (abhi sirf preview ka post-process).
- **Move / look: hamari koi joystick nahi.** Game ke apne keyboard & mouse controls (launcher ka Pojav Controls) use karo.
  Jo touch hamare UI par shuru nahi hota wo seedha game ko jata hai.

## Files
- `src/Core.*` — data + engine (keys, segments, easing, transitions, pins, save/load)
- `src/CameraLink.*` — `/camera` `/hud` `fov_set` `fade` command banata hai (executor ka intezaar)
- `src/Editor.*` — poora ImGui UI
- `src/TouchRouter.*` — multi-touch: UI ka touch consume, baaki game ko
- `src/GlHost.*` — EGL/GLES hook + ImGui frame loop + game frame capture
- `src/GameBridge.*` — **game se jodne wale hook points (TODO)**
- `src/MyMod.cpp` — mod entry (`pl::` API), Mod Menu module
- `third_party/imgui` — ImGui 1.87 (tumhare zip se; `imconfig.h` aur `imstb_rectpack.h` placeholder humne jode)
- `tests/` — host-side test (ImGui + engine bina phone ke)
- `docs/` — IDEA_AND_HISTORY, NEXT_STEPS, REFERENCES, Codex prompts

## Credits / license
Ye project original hai. `docs/REFERENCES.md` me jin open-source cheezon se seekha hai (Pojav Controls plugin, Do a Barrel Roll)
unke naam hain. **Do a Barrel Roll GPL-3.0 hai** — uska code copy karoge to apna mod bhi GPL-3 me open-source karna padega.
