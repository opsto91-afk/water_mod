# Screen POV v1.2.0 (LeviLauncher native mod)

Standalone, **camera-only** fixed POV mod — separate from the Water Tweaks mod and from the V4 Cine Mod.
Everything is set from the in-game Mod Menu; no file editing required.

## Usage (in game)

1. Import `pov_mod.levipack` in LeviLauncher and enable the mod.
2. Mod Menu (floating button) → **Screen POV**.
3. Toggle **Screen POV** ON → the render camera is **pinned to the pose** set below. Mouse/touch
   look is ignored while ON (fixed POV — deliberate), and the player body is not touched at all.
   Toggle OFF → the camera goes straight back to the game (always the safe state).
4. **Yaw (deg)** — absolute yaw (0 = south, 90 = west, 180 = north, 270 = east), −180..180.
   **Pitch (deg)** — absolute pitch (+ looks down, − looks up), −90..90.
   **Roll (deg)** — camera roll, + = right wing down, −180..180.
5. **Offset X / Y / Z** — position offset added to the player's eye position (relative mode,
   default), or absolute world coordinates (relative mode off). Requires the on-device probe to
   confirm the `view_offset` field in the CameraComponent (see `pov_mod.log`). If the probe does
   not confirm it, the position write stays OFF and orientation still works.
6. **Invert yaw** / **Invert pitch** — flip the sign of the applied angle. Device-test aid.
7. **Snap to game view** — copies the live view into the override (yaw/pitch = current, offsets/roll 0,
   relative mode).
8. **Live (release camera)** — hands the camera straight back to the game.

The body is never touched: no physics, no rotation, no movement — screen POV only.

Values persist in `pov.cfg` in the mod's data dir. Log:
`Android/media/org.levimc.launcher/kurumi/pov_mod.log` (fallback: the mod's data dir).

## How it drives the camera (v1.2)

Modelled on **Do-A-Barrel-Roll-Bedrock** (github.com/theaddonn/Do-A-Barrel-Roll-Bedrock,
`src/mod/hooks/Camera.cpp`) — the mod that successfully rolls the game camera on Windows — and on
**BedrockTools Freelook** (github.com/QYCottage/BedrockTools,
`src/modules/visual/freelook.cpp`) whose entt-registry walker we reuse to find the camera component
on Android (no symbols exist in libminecraftpe.so):

| Hook | Behaviour while override is ON |
|---|---|
| `UpdatePlayerFromCamera` (same 60.4 sig as Freelook) | original **skipped** — the body never syncs from the camera (as v1.1) |
| `LocalPlayerApplyTurnDelta` (same 60.4 sig as Freelook) | original **dropped** — mouse/touch look does nothing (as v1.1) |
| `RenderLevel` (BedrockTools `SignatureId::RenderLevel`) | **wrapped**: BEFORE the original render pass, find the LocalPlayer's own `CameraComponent` in the client ECS (entt registry walk, hash `0x4f6047c7`), snapshot it, write our yaw/pitch/roll **quaternion at +0x28** (and the view_offset at +0x00 if the probe confirms zeros). AFTER the original: snapshot restored byte-for-byte |

Why v1.1 could not work: it wrote into the `camera` pointer handed to `UpdatePlayerFromCamera`
(the player-side sync object) **and** skipped the only per-frame camera drive, so nothing render-side
ever saw a new orientation. Its stray 16-byte writes were also a prime suspect for the "noise/blur"
artifacts. v1.2 writes at the render pass itself, then restores — the same place DABR injects.

### On-device probe (one-time, first time the override is enabled)

The local player's `CameraComponent` layout (stride) is discovered at runtime because the game's
component size is not symbolized anywhere:

1. entt registry walk → find the pool for `CameraComponent` (hash `0x4f6047c7`) and the slot for the
   player's `EntityContext` entity.
2. Try every candidate stride (`0x38`..`0xa0`); score the quaternion at `+0x28`
   (unit-length invariant). The best candidate wins if `|len² − 1| ≤ 0.25`.
3. Log **all** candidate scores, the winning stride, and a hexdump of the component's first
   `0x40` bytes. If the first three floats are exactly `0.0f`, they are treated as `view_offset`
   and the position write is enabled; otherwise position stays OFF and only orientation is driven.
4. The render hook re-walks and revalidates the slot **every frame** before writing, and restores
   the original bytes after — components are never left dirty, even if the override is toggled
   mid-frame.

## Honest status

* **v1.2 is UNTESTED ON DEVICE.** It is built from the DABR injection pattern and the
  Freelook-proven registry walk, but nothing here is demonstrated on hardware yet.
* What to check on device: Mod Menu → Screen POV ON → the view must jump to the configured pose
  (and move when you drag Yaw/Pitch/Roll), and the body must stay put while the view is off-axis.
* If the view still does not move, open `pov_mod.log` — it is fully self-describing:
  * `renderLevel=` not growing → the `RenderLevel` hook never fires (sig mismatch on your build).
  * `probe=2` (failed) → the registry walk / component hash / stride probe failed — the
    per-stride `len2=` lines show exactly which candidates were tried and what they read.
  * `probe=1 ... viewOffsetZero=0` → orientation will work but position stays OFF; the
    `component bytes[0x00..0x40):` hexdump shows the actual layout to hand back for the next build.
  * `writes=` growing, view unchanged → the render path does not read the player's own component
    (the render camera is a separate ECS entity) — the log will show this via the detour-arg vs
    probe slot pointers (`detourArg=` vs `slot=`).
  * `dropped=` not growing → the look path goes through a different function on your build.
* Signatures are version-specific to BedrockTools 60.4-era builds. If BedrockTools' Freelook works
  on your device, the three signatures match your build by definition.

## Build

CI (this repository): the **Screen POV Build** workflow produces the `screen_pov` artifact
(`pov_mod.levipack`). Locally:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh        # inside pov_mod/

Water Tweaks / Infinite Flow / V4 Cine Mod CI builds were removed on 2026-11-11 at the user's
request; their source remains in this repository (see git history).

## Tests

`make -C tests run` — host-side unit tests for the camera math and config parsing.
