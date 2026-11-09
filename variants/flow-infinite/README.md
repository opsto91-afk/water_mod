# Infinite Flow (variant — offset pending)

Standalone variant of **Water Tweaks** whose **only** gameplay change is:

> Water keeps flowing and never stops — the spread distance cap (vanilla ≈ 7 blocks) is removed.

No water-delay / lava-delay / infinite-source changes. No other modules. One purpose.

## Status: NOT buildable yet — one verified offset is missing

The water/lava tick patch sites were found by RE of `libminecraftpe.so` (liquid tick worker at
`0x1088d268`). The "water stops spreading" behaviour is enforced by a small immediate somewhere in
the same code region (most likely a `cmp`/`subs` against the fluid "level", or a `mov` of the max
flow distance). **That instruction has not been identified yet** — it cannot be, without looking at
the actual bytes of your game build. Guessing it and patching blind would corrupt game memory.

## What must happen before this variant can be built

1. Get the game's `libminecraftpe.so` (or a bigger slice than the 4 KB one used for the water sites):
   ```bash
   python3 -c "open('slice2.bin','wb').write(open('libminecraftpe.so','rb').read()[0x1088c000:0x1088e200])"
   ```
   (~8.8 KB — covers the whole liquid tick worker and the sites we know.)
2. Run the RE helper:
   ```bash
   python3 tools/find_flow_sites.py slice2.bin --raw --raw-base 0x1088c000 --all > flow_report.txt
   python3 tools/find_flow_sites.py slice2.bin --raw --raw-base 0x1088c000   # flagged-only view
   ```
   It lists every `mov`/`cmp`/`sub` with an immediate in 1..16 and anything mentioning 7/8, with addresses.
3. From the flagged list, pick the instruction that guards the spread (context decides: a `cmp wX,#7`
   followed by a conditional branch out of the spreading path is the classic shape).
4. The variant gets a new verified patch table entry (same `Writer`/verify/restore machinery as
   Water Tweaks — guard bytes around it, dry-run default ON).
5. CI builds it, imp `infinite_flow.levipack` in LeviLauncher. Done.

## Design (fixed once the offset is known)

* Own `manifest.json` (`type: preload-native`, name `Infinite Flow`, single `lib`).
* Own table with exactly ONE patch site + 1–2 guards, default value chosen to match the instruction
  kind (e.g. raise the cap to a large immediate).
* Toggle ON/OFF restores vanilla bytes; values clamped; dry-run + Safe-Mode logic reused from water_mod.
* Local/single-player only (liquid sim is server-side).

## Known trade-offs (be honest about them)

* Infinite spread means the liquid sim keeps allocating new flowing blocks for as long as the world
  ticks — on big builds this can increase server/CPU load.
* Flowing water past a certain distance will still behave "thin" (level 1) but will not despawn early;
  visuals will show a long thin trail.
* Only valid for the exact Minecraft build the site was verified on (same caveat as all Water Tweaks offsets).

## Files

* `tools/find_flow_sites.py` — the RE helper (repo root). Everything else in this folder is documentation
  until the offset is confirmed.
