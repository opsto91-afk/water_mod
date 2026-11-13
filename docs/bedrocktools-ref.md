# BedrockTools SDK reference (verbatim facts) — fetched 2026-11-13

Upstream: **QYCottage/BedrockTools** (github.com/QYCottage/BedrockTools), **MIT License**,
Copyright (c) 2026 RadiantByte. Facts below are quoted/condensed from the repo (offsets and
signatures are facts, not copyrightable expression; freelook architecture used as a pattern).
Repo tarball snapshot: BedrockTools-main (main branch, fetched 2026-11-13).

## Files of interest

- `src/modules/visual/freelook.cpp` — the actual freelook implementation (see freelook-re.md Phase 9).
- `src/modules/visual/motionblur.hpp/.cpp` — `void MotionBlurCaptureCamera(void* camera, void* player);`
  Called by freelook in BOTH paths (active & passthrough). Our POV build should call it too.
- `include/bedrocktools/sdk/offsets/*.hpp` — the authoritative offset tables.
- `include/bedrocktools/sdk/client/ClientInstance.hpp` — ClientInstance SDK (players, entityContext).
- `src/core/memory/Signatures.cpp` + `include/bedrocktools/memory/Signatures.hpp` —
  signature table (SignatureDefinition{id, pattern, rtti, vtableSlot}).

## Render.hpp offsets (verbatim)

```
LevelRendererPlayer:
  mFogColorRed=0x41C, mFogColorGreen=0x420, mFogColorBlue=0x424
  mBaseFogStart=0x42C, mBaseFogEnd=0x430
  mCurrentFogDensityMax=0x45C
  mCamPos=0x614            <-- camera POSITION float3 (== our LRP+0x614)
  mSelectionOverlayMaterial=0x10A0

LevelRenderer:
  mLevelRendererPlayer=0x430

CameraComponent:
  mOrientation=0x28        <-- 16 B orientation floats; NO position field here
```

=> Camera **position** lives ONLY renderer-side (LRP+0x614). Camera **orientation**
   travels through UpdatePlayerFromCamera's `camera` (CameraComponent*) at +0x28.
   This is why freelook moves rotation but says nothing about position, and why our
   v1.3/v1.4 position writes (RenderLevel pre-orig / post-CamTick fn_0xb7d4274) did not move the view.

## Signatures (bedrocktools table, verbatim patterns)

UpdatePlayerFromCamera (== our v1.2 device-proven sig, byte-for-byte identical):
`? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? A9 ? ? ? 91 54 D0 3B D5 ? ? ? 52`

LocalPlayerApplyTurnDelta:
`? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 56 D0 3B D5 F3 03 00 AA F4 03 01 AA ? ? ? F9 ? ? ? F8 ? ? ? F9 ? ? ? F9`

GetPerspective:
`? ? ? A9 FD 03 00 91 ? ? ? F9 ? ? ? F9 ? ? ? F9 00 01 3F D6 ? ? ? F9 ? ? ? F9 ? ? ? A8 20 00 1F D6 ? ? ? A9 FD 03 00 91`

ClientInstanceGetLocalPlayer:
`? ? ? D1 ? ? ? A9 ? ? ? F9 ? ? ? 91 53 D0 3B D5 E8 03 00 AA ? ? ? 91 ? ? ? F9 ? ? ? 91 ? ? ? F8 ? ? ? ? ? ? ? 91 ? ? ? ? ? ? ? 36 ? ? ? 91 ? ? ? 52 ? ? ? ? ? ? ? ? E0 03 1F AA ? ? ? F9 ? ? ? F8 1F 01 09 EB ? ? ? 54 ? ? ? A9 ? ? ? F9 ? ? ? 91 C0 03 5F D6 ? ? ? ? C0 03 5F D6`

ActorGetHeadRotation (entt pool walk, double(?)-read):
`? ? ? F9 ? ? ? 52 00 E4 00 2F ? ? ? 72 ? ? ? A9 08 01 0B CB ? ? ? D3 ? ? ? 51 0C 01 0A 8A ? ? ? B9 6B 0D 0C 8B ? ? ? F9 ? ? ? F9 ? ? ? B1 ? ? ? 54 8B 15 0B 8B ? ? ? B9 BF 01 0A 6B ? ? ? 54 ? ? ? F9 7F 01 09 EB ? ? ? 54 ? ? ? F9 ? ? ? B4 ? ? ? A9 ? ? ? D3 8C 01 0A CB 7F 0D 8C EB ? ? ? 54 ? ? ? F8 ? ? ? B4 ? ? ? 92 ? ? ? 12 ? ? ? 92 ? ? ? B8 ? ? ? 32 48 01 08 4A 1F 01 0B 6B ? ? ? 54 ? ? ? D3 ? ? ? F9 01 E4 00 2F`

ActorSetHeadRotation (same walk + trailing store):
`? ? ? F9 ? ? ? 52 ? ? ? 72 ? ? ? A9 08 01 0B CB ? ? ? D3 ? ? ? 51 0C 01 0A 8A ? ? ? B9 6B 0D 0C 8B ? ? ? F9 ? ? ? F9 ? ? ? B1 ? ? ? 54 8B 15 0B 8B ? ? ? B9 BF 01 0A 6B ? ? ? 54 ? ? ? F9 7F 01 09 EB ? ? ? 54 ? ? ? F9 ? ? ? B4 ? ? ? A9 ? ? ? D3 8C 01 0A CB 7F 0D 8C EB ? ? ? 54 ? ? ? F8 ? ? ? B4 ? ? ? 92 ? ? ? 12 ? ? ? 92 ? ? ? B8 ? ? ? 32 48 01 08 4A 1F 01 0B 6B ? ? ? 54 ? ? ? D3 ? ? ? F9 ? ? ? 92 ? ? ? F8 ? ? ? B4 ? ? ? 92 ? ? ? 92 08 0D 09 8B ? ? ? 2D C0 03 5F D6`

RenderLevel (same as ours, Freelook/breadcrumbs/motionblur hook it too):
`? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5`

## What this means for pov_mod (the remaining unknown)

All position roads lead to **LRP+0x614** (renderer+0x430 chain). Proven from device:
- v1.2 log: LRP+0x614 readable, values track the world camera. ✓ (read works)
- v1.3: pre-orig RenderLevel write → static (overwritten later in the frame, by SOMETHING).
- v1.4: post-fn_0xb7d4274 write → camera still didn't move + controls corrupted
  (the `this` in that fn is likely NOT the LRP — possibly the InputComponent-family object).
- v1.5.0 SAFE: zero writes; renderLevel diag log prints LRP+0x614 every ~2 s.

UNKNOWN: exact frame order (who writes LRP+0x614 and when, relative to UpdatePlayerFromCamera
and RenderLevel), and whether fn_0xb7d4274's `this` == LRP.
=> Next step must be an OBSERVER build (zero writes): passthrough hooks that LOG call order
   + pointer identity (update-fn `camera`/`player` ptrs, LRP ptr from renderer+0x430, LRP+0x614
   values, tick-fn `this` ptr vs LRP ptr). One device run settles it. THEN the write build.
