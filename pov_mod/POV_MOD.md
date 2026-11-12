# Screen POV v1.4.0 (LeviLauncher native mod)

Standalone, **camera-position-only** mod — separate from the Water Tweaks mod and from the V4 Cine Mod.
Everything is set from the in-game Mod Menu; no file editing required.

> v1.4 has one job: **put the camera exactly where you want it (XYZ).**
> Your look (mouse/touch) stays 100% vanilla, and the player's body is never touched.
> Yaw/pitch/roll sliders are inert in v1.4 (orientation is vanilla).

## Usage (in game)

1. Import `pov_mod.levipack` in LeviLauncher and enable the mod.
2. Mod Menu (floating button) → **Screen POV**.
3. Toggle **Screen POV** ON → the camera position is overridden every frame as configured below.
   Toggle OFF → the camera goes straight back to the game (always the safe state).
4. **Offset X / Y / Z** — two modes (see the **Relative to player** toggle):
   * **Relative ON (default)** — the camera sits at the player's live camera position **+ (X, Y, Z)**
     and follows the player as they move (e.g. `Y=4` = a constant 4-block height boost).
   * **Relative OFF** — the camera is pinned to the exact world coordinates **(X, Y, Z)** and
     stays there while you walk around (spectator/drone style).
5. **Snap to game view** — zero the offsets and re-enable relative mode (vanilla view with zero offset).
6. **Live (release camera)** — hand the camera straight back to the game (same as toggle OFF).
7. Yaw / Pitch / Roll / Invert yaw / Invert pitch sliders are **inert in v1.4** (kept so old
   `pov.cfg` files still load).

Values persist in `pov.cfg` in the mod's data dir. Log:
`Android/media/org.levimc.launcher/kurumi/pov_mod.log` (fallback: the mod's data dir).

## How it drives the camera (v1.4)

Built from the user's **on-device reverse engineering** (Zaphkiel pseudo-C dumps of the exact
`libminecraftpe.so` on the test device, 2026-11-13). The dumps identified the two functions
that write the renderer's pose float3 at `LRP+0x614`:

| Hook | Behaviour while override is ON |
|---|---|
| `UpdatePlayerFromCamera` (same 60.4 sig as Freelook) | original **skipped** — the body never syncs from the camera |
| `LocalPlayerApplyTurnDelta` (same 60.4 sig as Freelook) | **pass-through** — you look around normally |
| `RenderLevel` (same 60.4 sig as Freelook) | **diagnostic only** — logs the live `LRP+0x614` value (now shows OUR value); no writes |
| `CamTick` = `fn_0xb7d4274` (on-device sig, 14 insns, UNIQUE) | the **per-frame pose writer** (eases/notifies/posts `moveRenderingArea` and WRITES the float3 at `+0x614..+0x61f` every frame). Wrapped: **original first**, then — only while ON — the 12-byte position float3 is overwritten (relative: live pose + offset; fixed: absolute world XYZ). Orientation/FOV bytes (`+0x620..+0x633`) are untouched. |
| `CamSetter` = `fn_0xb7c7268` (on-device sig, 14 insns, UNIQUE) | the **immediate pose-set funnel** (packet/script `/camera` path). Same post-orig overwrite — covers camera instructions too. |

Why v1.3 failed on device: it injected its XYZ **before** the original `RenderLevel` ran, and
the CamTick then **overwrote `+0x614` with vanilla's eased pose** later in the frame — the view
never moved. v1.4 writes **after** the writer runs, so every consumer (view matrix, reactive
pose listeners) sees our value while the override is ON.

Vanilla rewrites `+0x614` on every tick, so switching the mod **OFF needs no restore hack** —
toggle OFF and the very next frame is pure vanilla again.

### Honest status

* **v1.4 is UNTESTED ON DEVICE.** It is built from on-device RE of this exact build, and the
  write now happens **after** the pose writer runs. What to check on device:
  1. `pov_mod.log` shows `hooks install: ... CamTick=ok CamSetter=ok` shortly after launch.
  2. The `tick=` counter keeps growing (≈60/s while in-game).
  3. `camTickDetour FIRST call: lrp=...` and `override: FIRST LRP+0x614 write cur=... target=...`
     appear within a frame or two of switching the toggle ON.
  4. **The view jumps** to the configured XYZ / offset while the game keeps running normally;
     toggling OFF returns vanilla view within a frame.
* If `CamTick=ok` but the view still doesn't move, paste the log — the next step is to also
  pin whatever else caches the pose (e.g. the atomic pose slot at `this+0x978 → +0xa88`, or a
  view-matrix snapshot taken earlier in the frame). This is a one-function addition, not a redesign.
* CamTick/CamSetter signatures are on-device unique for this build (matches=1, quality ≥515);
  the three BedrockTools 60.4 sigs cover the rest (MC 1.26.52).

## Build

CI (this repository): the **Screen POV Build** workflow (job `pov-mod`) produces the `screen_pov` artifact
(`pov_mod.levipack`) on every push. Locally:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh        # inside pov_mod/

Water Tweaks / Infinite Flow / V4 Cine Mod CI builds were removed on 2026-11-11 at the user's
request; their source remains in this repository (see git history).

## Tests

`make -C tests run` — host-side unit tests for the camera math and config parsing.
