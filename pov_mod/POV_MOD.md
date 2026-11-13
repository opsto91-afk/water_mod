# Screen POV v1.6.0 (LeviLauncher native mod) — OBSERVER BUILD

> **v1.6.0 does NOT move the camera.** It is an instrument, not a feature.
> It answers ONE question that the v1.4 postmortem left open:
> **who writes the renderer camera pose (LRP+0x614), and when — and is
> `fn_0xb7d4274`'s `this` the LRP at all?**

## Why yet another build

Every camera-moving attempt so far failed on device, so nothing ships blind again:

* v1.2/v1.3 — wrote the pose *before* the original `RenderLevel`; something
  rewrote it later in the frame → static screen.
* v1.4 — wrote the pose *after* `fn_0xb7d4274`; the camera didn't move AND the
  movement/attack controls broke. Theory: that function's `this` is not (only)
  the renderer, or its float3 is consumed elsewhere.
* v1.5.0 — zero writes, game vanilla. Inert; device-confirmed.
* v1.6.0 (**this build**) — zero writes, **logs only**.

## What it does (nothing else)

Four pass-through hooks, all calling the original first/always, **none writes a
single byte of game memory**:

| Hook | Logs |
|------|------|
| `UpdatePlayerFromCamera` | call count, pointers, global seq no. |
| `RenderLevel` | `renderer` ptr, `LRP = renderer+0x430` ptr, LRP+0x614 float3 **pre/post** original (first 48 frames), LRP ptr cached for comparisons |
| `CamTick (fn_0xb7d4274)` | `this` ptr, `this == LRP?`, `this+0x614` pre/post original, whether the **original** changed `this+0x614` |
| `CamSetter (fn_0xb7c7268)` | same as CamTick |

A global sequence counter is printed with every line, so the exact interleaving
(tick vs render vs updatePlayer) can be reconstructed. Missed signatures simply
log `MISSING` and the rest keep working. Crashes, if any, land in the usual
`pov_mod.log` crash tracer.

## What we need from you (one play session)

1. Import `pov_mod.levipack` from CI artifact `screen_pov` (workflow
   **Screen POV Build**, PR #1), remove the old one.
2. **Play 1–2 minutes**: walk around, turn the camera, maybe attack once.
3. Send `Android/media/org.levimc.launcher/kurumi/pov_mod.log`
   (filter for lines starting with `renderLevel` / `camTick` / `camSetter` /
   `updatePlayer`).

## How to read the log

* `camTick ... this==LRP=1` and `this+0x614 changed-by-original=1` →
  CamTick **is** the pose writer; v1.4's theory was right about the object —
  next step is a write placed *after* its original, on top of an untouched
  input path, plus reproducing the v1.4 control corruption with it DISABLED to
  prove causality.
* `this==LRP=0` → the v1.4 write landed in the **wrong object** — explains the
  mirrored joystick; the real pose writer is still unidentified and we hunt it
  from the seq order (whatever runs between `updatePlayer` and `camTick` and
  touches LRP+0x614).
* `renderLevel ... changed-by-original=1` → the render pass itself rewrites the
  pose; the write point must move *inside/after* RenderLevel.

## Honest status

* **The POV camera does NOT work in v1.6.0.** Deliberate.
* Your game stays 100% vanilla; X/Y/Z are saved but inert.
* No claims beyond the log. The next (write-capable) build ships only after
  this log pins the pose writer, and is then verified on device by you.
