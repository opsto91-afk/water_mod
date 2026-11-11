# Water Tweaks v0.3 (LeviLauncher native mod)

Changes how fast water/lava update and how many sources make a new source, by patching 5 ARM64
instructions in `libminecraftpe.so` (found with Zaphkiel in the main liquid tick worker `0x1088d268`).

> **Stale artifacts:** `fix_water_mod.zip` and `2refix_water_mod.zip` in this folder are the OLD v0.2
> source snapshots that crash the game at UI load time. Do **not** build or import them. Use this
> repository (v0.3.0) as-is.

## The crash (why v0.2.x died right when the game screen UI loads) — root cause

LeviLaunchroid bundles `LiteLDev/preloader-android` at commit `cc08b40` (a post-0.2.3 `main`, see
`app/src/main/cpp/preloader` in `LiteLDev/LeviLaunchroid`). In that build `pl::modmenu::ModuleInfo`
has a **new trailing member** `onKeybind` (a `std::function`, 32 bytes) that plain tag `0.2.2` does
not have.

v0.1/v0.2/v0.2.1 were all built against `GIT_TAG 0.2.2`. The mod passed its 0.2.2-sized `ModuleInfo`
by reference to the game process's `pl::modmenu::registerModule()`. That code is compiled from the
bundled preloader's **newer** headers, so it unconditionally executes

    entry.on_keybind = info.onKeybind;

i.e. it reads a `std::function` starting exactly at `sizeof(ModuleInfo_0.2.2)` — 32 bytes of
**uninitialised stack memory** — and copies it into the registered module. The copy constructor
calls the garbage "manager" function pointer → jump to an invalid address → the process dies a
couple of seconds after `enable()` returns, i.e. exactly while the game is bringing up the
screen/UI. Nothing Java-side throws and the game's own crash reporting never engages, so the
launcher shows nothing and no crash log appears — the game just cuts out.

Reproduced and confirmed in isolation: an AddressSanitizer build of `entry.on_keybind = info.onKeybind`
against a 0.2.2-layout `ModuleInfo` heap object reports `heap-buffer-overflow READ ... 16 bytes to the
right of 224-byte region` inside `std::function::operator=`; building both sides against the bundled
commit (`cc08b40`) is clean (`onKeybind` defaults to empty, `if (!mod.on_keybind) continue;`).

This also explains why the v0.2 "defer registration by 3 s" change did not help: it only moved the
wild read to t≈3 s after enable — i.e. from "crashes during enable" to "crashes as the game UI is
coming up". And with the launcher enabling mods on the main thread inside `onCreate` *before*
`super.onCreate()`, that is precisely "when it's the turn of the game screen UI to load".

### What v0.3.0 changes

* `CMakeLists.txt` now pins `FetchContent` to the exact commit LeviLaunchroid ships
  (`cc08b40a31471d3e2dedb267f5f17b28da2b0604`), not `0.2.2` (and not tag `0.2.3` — it pre-dates the
  `onKeybind`/`ConfigType` additions too).
* Mod Menu registration is back to happening **synchronously in `enable()`**, exactly like the
  official `examples/full-cpp-mod` reference. The 3-second registration thread is removed.
* `mMenuRegistered` is now a `std::atomic<bool>`; `disable()` unregisters the module as before.
* Version bumped to `0.3.0`. The offsets, guards, dry-run mode, armed-flag SAFE MODE and crash
  tracer are unchanged.

**For any future build:** the `pl::` ABI is NOT stable across preloader releases. Always build
against the exact `preloader-android` commit recorded in the launcher's
`app/src/main/cpp/preloader` submodule hash — otherwise `ModuleInfo` (and anything else the SDK
grows) silently changes size and the same class of crash returns.

## Before building (PC / Codespace)
    python3 tools/check_so.py /path/to/libminecraftpe.so     # every line must say OK

## Build
Run the repository's **Water Mod Build** workflow in GitHub Actions (or push a change).
When it succeeds, download the `water_mod` artifact and import `water_mod.levipack`
in LeviLauncher.

To build locally, install the Android NDK and run:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh

## Use (v0.3: Mod Menu sliders)
The module is registered synchronously in `enable()` (the launcher enables mods before the game's
activity UI is created). Then use
Mod Menu -> "Water Tweaks": toggle + sliders (water delay, lava delay overworld / nether, sources for infinite water)
+ "Dry run" toggle. Values are saved to water.cfg. Slider changes are applied ~0.3 s later.

### First test (crash hunting) - do it in this order
1. Turn **Dry run** ON, then the module ON -> log must say `verify OK ... DRY RUN`. Nothing is written.
2. Dry run OFF -> log says `writing patches ...` and `patched 5 instruction(s)`.
3. Open `Android/media/org.levimc.launcher/kurumi/water_mod.log` - every step is flushed to disk, so even after a
   crash the last line shows how far it got. (Preloader's own log goes to Android logcat, not to the launcher.)
3b. Crash tracer: on SIGSEGV/SIGBUS/SIGILL/SIGABRT/SIGFPE the log gets a `*** CRASH` block with fault address,
   pc and lr as `libminecraftpe.so+0x...` / `water_mod.so+0x...` and the `stage` we were in. Send that block.
4. If the previous run died after patching, the next start logs `SAFE MODE` and writes nothing until you delete
   `water_mod.disabled` in the mod's data dir.

## Old text file way (still read at start)
1. Start the game once, edit `water.cfg` in the mod's data folder (created on first load):
       water_delay=5            # ticks, vanilla 5   (1..2000)
       lava_delay_overworld=30  # vanilla 30
       lava_delay_nether=10     # vanilla 10
       infinite_source_count=2  # vanilla 2          (1..8)
2. Mod Menu -> "Water Tweaks" -> ON. Edit the file -> OFF, ON to re-apply.
3. The mod log tells what happened ("patched 5 instruction(s)", or "NOT patching: ...").

## Safety
* Each site is checked against the exact vanilla instruction first. Different Minecraft build =>
  nothing is written ("NOT patching: guard mismatch ...").
* OFF restores the vanilla instructions.
* Local / single-player worlds only: liquid ticks run on the server side.

## Tests
`make -C tests run` (pure patch logic, runs on any PC).

## Not verified yet
* The v0.3.0 build has been ABI-verified against the launcher's bundled preloader sources and
  compiles cleanly against them, but still needs one on-device run.
* `mprotect` on the game's code pages may be refused on some devices (patching then fails safely
  with "FAILED to write ..." in the log instead of crashing).
* Offsets are for ONE Minecraft build. A signature scan is the next step to survive updates.

## Screen POV (standalone mod in `pov_mod/`)

A separate, camera-only mod: set your own screen POV from the in-game Mod Menu
(yaw / pitch / position offsets, relative-to-player or absolute) — body never touched.
It is its **own** `.levipack` (`pov_mod.levipack`, CI artifact `screen_pov`), independent of the
Water Tweaks mod above. See `pov_mod/POV_MOD.md` for usage and the honest on-device status.
The CI workflow now builds only this mod (Water Tweaks / Infinite Flow / V4 Cine Mod CI builds were
removed on 2026-11-11 at the maintainer's request; their source remains in this repository).
