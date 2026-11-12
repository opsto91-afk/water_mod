# Screen POV v1.5.0 (LeviLauncher native mod) — SAFE BUILD

> **v1.5.0 does NOT move the camera.** It exists to (1) return your game to a
> 100% unbroken state and (2) keep only the XYZ settings. The camera override is
> disabled until the pose-consumer RE is finished and verified on device.

## Why

v1.4 (2026-11-13) hooked two functions taken from static dumps of this build
(`fn_0xb7d4274` / `fn_0xb7c7268`, on the theory they write the renderer's pose
float3). On device, with the dump-derived theory unverified beyond the disasm:

* the camera **never moved**, and
* the player **movement / attack controls broke** — the joystick was mirrored
  ("forward walks back, left walks right, forward also drifts right") and the
  attack button behaved reversed.

Conclusion: the object those functions operate on is **not** (only) the renderer
camera pose — or the writes landed in a component the input/movement system
consumes. The hooks were removed in v1.5.0.

## What v1.5.0 does

* **Zero writes to game memory — ever.**
* The ONLY hook is `RenderLevel` (60.4 sig Freelook uses) as a **read-only,
  100% pass-through** diagnostic: every ~2 s it logs the live camera float3
  (`renderer+0x430 → LRP+0x614`) to `pov_mod.log`.
* `UpdatePlayerFromCamera` / `LocalPlayerApplyTurnDelta` are **not** hooked.
* Mod Menu -> "Screen POV": **X / Y / Z sliders + Relative-to-player toggle +
  Reset to vanilla** — all SAVED but **inert** (they take effect only once the
  fixed camera build ships).
* All legacy sliders (Yaw / Pitch / Roll / Invert yaw / Invert pitch) are gone;
  old `pov.cfg` orientation keys are accepted and ignored.
* Crash tracer + `pov_mod.log` (Kurumi path) kept.

## Install over v1.4

1. Remove/overwrite `screen_pov` from the launcher (or delete the old
   `pov_mod.levipack`), import this build, start the game.
2. Delete the old `pov.cfg` in the mod's data dir if you want a clean config
   (orientation values are ignored anyway).
3. Check `Android/media/org.levimc.launcher/kurumi/pov_mod.log` — the first line
   says `Screen POV v1.5.0 start` and `SAFE MODE: camera override DISABLED`.
4. Your controls will behave **exactly like vanilla Minecraft**.

## Honest status

* **The POV camera does NOT work in v1.5.0.** This is deliberate: no unverified
  writes reach the game.
* What is still known-good: the renderer camera position float3 at
  `LRP+0x614` is readable and tracks the world camera correctly (v1.2/v1.4
  logs). A future build must pin the pose **where the renderer actually reads
  it each frame** — but BOTH known write points failed on device already
  (RenderLevel-before-orig = static screen in v1.3; post-`fn_0xb7d4274` = broken
  controls in v1.4), so the frame order / target object must be pinned down
  FIRST, with on-device confirmation, before any write ships again.
* Every artifact from the dump session lived outside the repository and was lost
  when a sandbox reset wiped it; the remaining RE notes are in
  `docs/freelook-re.md` (including the v1.4 postmortem).
* Next step needs **on-device verification only**: no blind rebuilds. Either
  re-dump the camera subsystem (the two `0xb7d4xxx/0xb7c7xxx` functions and the
  callers that pass `this` = the camera object), or paste `pov_mod.log` from the
  v1.4 session (it contains the `override: FIRST LRP+0x614 write ...` and the
  periodic `renderer camera pos` lines) — that log alone will show whether the
  renderer ever consumed our values.

## Build

CI (this repository): the **Screen POV Build** workflow (job `pov-mod`) produces
the `screen_pov` artifact (`pov_mod.levipack`) on every push. Locally:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh        # inside pov_mod/

## Tests

`make -C tests run` — host-side unit tests for the camera math and config parsing.
