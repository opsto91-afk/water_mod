# Infinite Flow (experimental variant)

**One feature, nothing else:** flowing water never stops spreading — the vanilla
"distance from source ≤ 7" cap is raised to your `spread_limit` (default 15, max 15).

> Status: EXPERIMENTAL build. Byte offsets verified against one Minecraft build only
> (the same build the Water Tweaks offsets come from). If the offsets don't match your
> build, the mod verifies first and refuses to write ANYTHING ("NOT patching: ...").
> Local / single-player only — liquids tick server-side.

## What it patches (all in the liquid tick worker at 0x1088d268)

| Site | Vanilla | Patched | Purpose |
|---|---|---|---|
| 0x1088d64c | `cmp w10, #7` | `cmp w10, #N` | path A: the invalidating `csinv -> w24 = -1` check |
| 0x1088da80 | `cmp w25, #7` | `cmp w25, #N` | path B: gate 1 |
| 0x1088da88 | `cmp w24, #7` | `cmp w24, #N` | path B: gate 2 |
| 0x1088e13c | `cmp w24, #7` | `cmp w24, #N` | path C: outer guard |
| 0x1088e144 | `cmp w22, #7` | `cmp w22, #N` | path C: inner guard (before the tick-scheduling call) |
| 0x1088d980 | `csel w24, w0, w8, gt` | `mov w24, w0` | packer: stops re-typing distance>7 water as "falling" (keep_flow_type=1) |

N = `spread_limit` (1..15). Guards = the Water Tweaks build markers (same Minecraft build).

## Why 15 is the cap (read this before asking for "4095")

Vanilla fluid distance is 0..7 and **bit 3 (value 8) marks the "falling" fluid kind**.
The packer instruction `csel w24, w0, w8, gt` at 0x1088d980 actively converts a distance
above 7 into `distance + 8` — i.e. into the falling kind. That means:

* raising the `cmp` caps alone only makes distant water **"falling" water**, which does not
  spread horizontally the way flowing water does;
* with `keep_flow_type=1` (`mov w24, w0`) that re-typing is disabled and distances 8..15 can
  remain flowing — but 8..15 re-enters the falling nibble anyway at pack time is avoided only
  if the packed field is ≥ 4 bits wide beyond the falling bit, which we have NOT verified.
  15 is therefore the largest value that does not overflow a 4-bit field.

If the game misbehaves past some lower value, set `spread_limit` lower — or toggle the module
OFF to restore every site to vanilla bytes.

## Test protocol (in-game)

1. Enable **Dry run** first → `infinite_flow.log` must say `verify OK ... DRY RUN`. Nothing is written.
2. Dry run OFF → `writing patches ...` + `patched 6 instruction(s)` (5 cmp + keep_flow_type).
3. Flat 16-block trench of source blocks: count how far water spreads. Vanilla = 7.
4. Toggle module OFF → `restored 6 instruction(s) to vanilla`. Water should behave vanilla again.

## Where the log lives

`Android/media/org.levimc.launcher/kurumi/infinite_flow.log`

## Remaining unknowns

* Whether distances 8..15 are truly accepted by the block-state packer as "flowing" (needs
  one in-game run with keep_flow_type=1 and spread_limit=15).
* Whether a future Minecraft build moves any of the six sites (signature scan still pending).

## Files

* `variants/flow-infinite/src/InfiniteFlowMod.cpp` — the module (single feature, full safety machinery)
* `variants/flow-infinite/manifest.json` — `Infinite Flow` v0.1.0
* `tools/find_flow_sites.py` — RE helper used to locate the `cmp #7` candidates (repo root)
* `tools/ghidra/FindSpreadCap.py` — same, as a Ghidra headless postScript
