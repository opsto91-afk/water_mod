# Screen POV v1.0.0 (LeviLauncher native mod)

Standalone, **camera-only** POV mod — separate from the Water Tweaks mod and from the V4 Cine Mod.
Everything is set from the in-game Mod Menu; no file editing required.

## Usage (in game)

1. Import `pov_mod.levipack` in LeviLauncher and enable the mod.
2. Open the Mod Menu (the floating button) → **Screen POV**.
3. Toggle **Screen POV** ON → the camera now holds the pose configured below.
   Toggle it OFF → the camera is fully back to the game (this is always the safe state).
4. **Yaw (deg)** — absolute yaw (0 = south, 90 = west, 180 = north, 270 = east), −180..180.
   **Pitch (deg)** — absolute pitch (+ looks down, − looks up), −90..90.
5. **Offset X / Y / Z** — position offset. With **Relative to player** ON (default) the offset is
   added to the player's eye position; toggle it OFF to enter absolute world coordinates.
6. **Snap to game view** — copies the current live game view into the override (yaw/pitch = live
   values, offsets 0, relative mode).
7. **Live (release camera)** — hands the camera straight back to the game without leaving the menu.

The body is never touched: no physics, no rotation, no movement, no reach change — screen POV only.

Values persist in `pov.cfg` in the mod's data dir. Log:
`Android/media/org.levimc.launcher/kurumi/pov_mod.log` (fallback: the mod's data dir).

## How it drives the camera

* Hooks installed by a watcher thread once `libminecraftpe.so` is mapped:
  * `UpdatePlayerFromCamera` (id 0x8c pattern) → **after** the original, re-applies the override
    to the camera orientation quaternion (camera + `0x28`).
  * `RenderLevel` (id 0x23 pattern) → **before** the original, writes the override eye position
    to the render-side camera position (renderer → `+0x430` → `+0x614`).
* The player pointer is captured from the `UpdatePlayerFromCamera` call itself (the
  ClientInstanceUpdate signature never matched on this build, so it is not needed).
* All writes are `mincore()`-guarded; the original functions are always called.

## Honest status (as of v1.0.0)

* The override path has **not been demonstrated on device yet**. If the Mod Menu toggle works but
  the view does not change, open `pov_mod.log`:
  * `update=` / `render=` counters — are the hooks firing at all?
  * `poseW=` / `camW=` — are the guarded writes landing, or skipped (offsets/signatures wrong
    for this build)?
  * `world` offsets used (`Camera +0x28`, `RendererPlayer +0x614`) come from the 60.4 public
    offset tables and may not match your exact build.
* Signatures come from the BedrockTools signature table for 60.4. If the signatures resolve but
  nothing moves, send the log and the offsets will be the next thing to revisit.

## Build

CI: the repository's **Water Mod Build** workflow produces the `screen_pov` artifact
(`pov_mod.levipack`). Locally:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh        # inside pov_mod/

## Tests

`make -C tests run` — host-side unit tests for the camera math and config parsing.
