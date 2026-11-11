# Screen POV v1.3.0 (LeviLauncher native mod)

Standalone, **camera-position-only** mod — separate from the Water Tweaks mod and from the V4 Cine Mod.
Everything is set from the in-game Mod Menu; no file editing required.

> v1.3 has one job: **put the camera exactly where you want it (XYZ).**
> Your look (mouse/touch) stays 100% vanilla, and the player's body is never touched.
> Yaw/pitch/roll sliders are inert in v1.3 (orientation is vanilla).

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
7. Yaw / Pitch / Roll / Invert yaw / Invert pitch sliders are **inert in v1.3** (kept so old
   `pov.cfg` files still load).

Values persist in `pov.cfg` in the mod's data dir. Log:
`Android/media/org.levimc.launcher/kurumi/pov_mod.log` (fallback: the mod's data dir).

## How it drives the camera (v1.3)

Modelled on the 60.4 sig-hook approach, but the injection is a **direct memory write into the
renderer's own camera record** — no entt registry walk, no CameraComponent, no quaternion probing:

| Hook | Behaviour while override is ON |
|---|---|
| `UpdatePlayerFromCamera` (same 60.4 sig as Freelook) | original **skipped** — the body never syncs from the camera |
| `LocalPlayerApplyTurnDelta` (same 60.4 sig as Freelook) | **pass-through** — you look around normally |
| `RenderLevel` (same 60.4 sig as Freelook) | **wrapped**: BEFORE the original render, resolve `renderer + 0x430` (`LevelRenderer::mLevelRendererPlayer`) → `LevelRendererPlayer`, snapshot the **float3 camera position at +0x614**, write our XYZ (relative: live pos + offset; fixed: absolute). AFTER the original render, restore the 12 bytes byte-for-byte. |

Why v1.2 failed on device: it wrote into the `CameraComponent` found via an entt registry walk
using hash `0x4f6047c7` — a hash from Do-A-Barrel-Roll's **Windows** build. The Android build uses
Minecraft's custom entt hash, the walk resolved nothing, the on-device probe never won a stride,
`writes=0` on every frame and `injectFail` climbed without bound (all in `pov_mod.log`).
The LRP+0x614 read path, by contrast, logged the live world camera position correctly every 2s
(e.g. `173.569 64.620 18.033` → `171.156 64.620 17.902`), which is why v1.3 writes there directly.

### Honest status

* **v1.3 is UNTESTED ON DEVICE.** It is built from a verified *read* path (LRP+0x614), but the
  *write* path is new. What to check on device:
  1. `pov_mod.log` must show `inject: FIRST direct LRP pos write cur=... target=...` shortly
     after you enable the override.
  2. The `injects=` counter must keep growing (one per frame).
  3. **View jumps** to the configured XYZ / offset while the game keeps running normally.
  4. OFF → the camera instantly returns to vanilla.
* If the log shows injects growing but the view does NOT move, the renderer is not reading the
  position from this record at render time on this build (it may read a precomputed view matrix
  built earlier in the frame). **Paste the log** — the next iteration will hook the function that
  builds the view matrix instead. This is a one-function move, not a redesign.
* Signatures are version-specific to BedrockTools 60.4-era builds (MC 1.26.52).

## Build

CI (this repository): the **Screen POV Build** workflow produces the `screen_pov` artifact
(`pov_mod.levipack`) on every push. Locally:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh        # inside pov_mod/

Water Tweaks / Infinite Flow / V4 Cine Mod CI builds were removed on 2026-11-11 at the user's
request; their source remains in this repository (see git history).

## Tests

`make -C tests run` — host-side unit tests for the camera math and config parsing.
