# Screen POV v1.7.0 (LeviLauncher native mod) — position override

> **v1.7.0 moves the camera POSITION (X/Y/Z) only.** Orientation,
> movement and controls stay vanilla. Toggle **OFF** ⇒ fully vanilla.

## How it works (device-verified design)

v1.6.0 OBSERVER run on this exact game build proved:

* `CamTick (fn_0xb7d4274)` is **never called** (0 invocations in the session) — not hooked.
* `CamSetter (fn_0xb7c7268)` is called **every rendered frame** with `this == the LRP`
  (pointer-identical to `renderer+0x430`'s LevelRendererPlayer) — this is the pose funnel.
* The renderer camera pose float3 lives at **LRP+0x614**; it is rewritten tick-side
  (only when it changes), never inside camSetter/RenderLevel originals.
* `UpdatePlayerFromCamera` was v1.4's control-corruption bug (it skipped the original) —
  **never hooked in v1.7.0**.

So v1.7.0 installs exactly two hooks:

1. **CamSetter** — calls the original first, then (toggle ON only) overwrites the
   12-byte float3 at `LRP+0x614`. Relative mode composes from a disambiguated
   *vanilla snapshot* (entry pose compared against what we wrote last frame) so the
   offset can never accumulate frame-over-frame; absolute mode writes the world
   XYZ verbatim. Toggle OFF performs a one-time vanilla restore if our pinned
   pose is still in place.
2. **RenderLevel** — read-only diagnostic pulse (~2 s) for the log. Zero writes.

## Mod Menu

* **Toggle "Screen POV"** — OFF = fully vanilla (zero writes).
* **Offset X / Y / Z** — relative: camera offset in blocks. Absolute (relative OFF):
  world XYZ coordinates.
* **Relative to player** — ON: camera = vanilla pose + offset (follows you).
  OFF: camera pinned at the configured world XYZ.
* **Reset to vanilla** — offsets := 0, relative ON.

## Test (the only acceptance gate)

1. Import the `screen_pov` artifact, remove the old build.
2. Toggle ON with default offsets — the view should JUMP (offset is 4,7,-8 in the
   existing cfg; use Reset to vanilla for a neutral test, then set X/Y/Z small).
3. Walk around: the view should follow at the offset; movement, look and attack
   must feel exactly like vanilla.
4. Toggle OFF: the view must return to the normal position.
5. If anything is off (view doesn't move / weird feel / crash): toggle OFF and
   send `kurumi/pov_mod.log`.

## Honest status

* Position override only; orientation/FOV/item sway are NOT implemented (by design).
* The write point is the best device-verified candidate; if the view still doesn't
  react on-device, that proves the renderer consumes the pose from somewhere else,
  and the next RE step is identifying that consumer — not more writes.
