# Screen POV v1.1.0 (LeviLauncher native mod)

Standalone, **camera-only** fixed POV mod — separate from the Water Tweaks mod and from the V4 Cine Mod.
Everything is set from the in-game Mod Menu; no file editing required.

## Usage (in game)

1. Import `pov_mod.levipack` in LeviLauncher and enable the mod.
2. Mod Menu (floating button) → **Screen POV**.
3. Toggle **Screen POV** ON → the camera now **holds the pose** set below. Mouse/touch look
   input is **ignored** while ON (fixed POV — this is deliberate), and the player body is pinned
   too (it never rotates). Toggle OFF → the camera and all look input go back to the game
   (always the safe state).
4. **Yaw (deg)** — absolute yaw (0 = south, 90 = west, 180 = north, 270 = east), −180..180.
   **Pitch (deg)** — absolute pitch (+ looks down, − looks up), −90..90.
5. **Offset X / Y / Z** — position offset added to the player's eye position (relative mode,
   default), or absolute world coordinates (relative mode off). Follows the player while walking.
6. **Invert yaw** / **Invert pitch** — flip the sign of the applied angle. Device-test aid:
   if the view ends up mirrored/backwards on your build, flip these instead of rebuilding.
7. **Snap to game view** — copies the live view into the override (yaw/pitch = current, offsets 0,
   relative mode).
8. **Live (release camera)** — hands the camera straight back to the game.

The body is never touched: no physics, no rotation, no movement — screen POV only.

Values persist in `pov.cfg` in the mod's data dir. Log:
`Android/media/org.levimc.launcher/kurumi/pov_mod.log` (fallback: the mod's data dir).

## How it drives the camera (v1.1)

Modelled directly on **BedrockTools Freelook** (github.com/QYCottage/BedrockTools,
`src/modules/visual/freelook.cpp`) — the hooks below are the **exact same two signatures**
Freelook uses on 1.21.x / 60.4:

| Hook | Signature (BedrockTools `SignatureId`) | Behaviour while override is ON |
|---|---|---|
| `UpdatePlayerFromCamera` | `? ? ? D1 … 91 54 D0 3B D5 ? ? ? 52` — per-frame player↔camera sync | original is **skipped** (like Freelook); we write our quaternion into the camera component (`camera + 0x28`) |
| `LocalPlayerApplyTurnDelta` | `? ? ? D1 … 91 56 D0 3B D5 F3 03 00 AA F4 03 01 AA …` — mouse/touch look delta | original is **dropped** entirely, so the look input does nothing (body never rotates either) |

Why this matters / what we got wrong in v1.0:

* v1.0 hooked the same `UpdatePlayerFromCamera` sync but **called the original unconditionally**
  and then overwrote orientation. It also hooked a signature we believed was `RenderLevel`
  (`… 91 57 D0 3B D5`) — BedrockTools maps that exact pattern to its `RenderLevel` id, but in this
  build nothing camera-related happens there; the eye-position write before it could not have
  any visual effect. Both removed in v1.1.
* v1.0 never hooked `LocalPlayerApplyTurnDelta`, so the mouse look input still fed the game's own
  camera path every frame — fighting our write. v1.1 drops the delta while ON (mirroring how
  Freelook restores/pins player rotation), giving one single writer of the view: this mod.

Offsets still used (BedrockTools `sdk/offsets`, MIT): camera quaternion at `CameraComponent + 0x28`.
`mCamPos`/`LevelRendererPlayer` offsets are no longer used at all.
Every write is `mincore()`-guarded; with override OFF the two functions pass through untouched.

## Honest status

* v1.1 is built from the Freelook-proven mechanism, but it is **still not demonstrated on device**.
* If the view does not change after toggling Screen POV ON, open `pov_mod.log`:
  * `update=` counter not growing → the `UpdatePlayerFromCamera` hook is never called
    (signature matched nothing / wrong function on your build).
  * `update=` growing but `poseW=` stuck at 0 → our camera-write is being skipped by the
    `mincore()` guard (the `camera` pointer from the hook is not readable — wrong signature/build).
  * `dropped=` not growing → the turn-delta hook is installed but the look path goes through a
    different function on your build (then the view would still rotate on look).
  * Everything grows but the view is mirrored/backwards → use **Invert yaw / Invert pitch**.
* Signatures are version-specific to Bedrock-BedrockTools 60.4-era builds. If BedrockTools'
  Freelook works on your device, these two signatures match your build by definition.

## Build

CI (this repository): the **Screen POV Build** workflow produces the `screen_pov` artifact
(`pov_mod.levipack`). Locally:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh        # inside pov_mod/

Water Tweaks / Infinite Flow / V4 Cine Mod CI builds were removed on 2026-11-11 at the user's
request; their source remains in this repository (see git history).

## Tests

`make -C tests run` — host-side unit tests for the camera math and config parsing.
