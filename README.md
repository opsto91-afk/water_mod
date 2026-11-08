# Water Tweaks (LeviLauncher native mod)

Changes how fast water/lava update and how many sources make a new source, by patching 5 ARM64
instructions in `libminecraftpe.so` (found with Zaphkiel in the main liquid tick worker `0x1088d268`).

## Before building (PC / Codespace)
    python3 tools/check_so.py /path/to/libminecraftpe.so     # every line must say OK

## Build
Run the repository's [Water Mod Build workflow](.github/workflows/build.yml) from GitHub
Actions (or push a change). After it succeeds, download the `water_mod`
artifact and import `water_mod.levipack` in LeviLauncher.

To build locally, install the Android NDK and run:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh

The script writes `dist/water_mod.levipack` and the unpacked mod folder to `dist/`.

## Use
1. Start the game once, edit `water.cfg` in the mod's data folder (created on first load):
       water_delay=5            # ticks, vanilla 5   (1..2000)
       lava_delay_overworld=30  # vanilla 30
       lava_delay_nether=10     # vanilla 10
       infinite_source_count=2  # vanilla 2          (1..8)
2. The Mod Menu module registers about 3 seconds after the mod is enabled. Then choose
   "Water Tweaks" -> ON. Edit the file -> OFF, ON to re-apply.
3. The mod log tells what happened ("patched 5 instruction(s)", or "NOT patching: ...").

## Safety
* Each site is checked against the exact vanilla instruction first. Different Minecraft build =>
  nothing is written ("NOT patching: guard mismatch ...").
* OFF restores the vanilla instructions.
* Local / single-player worlds only: liquid ticks run on the server side.

## Tests
`make -C tests run` (pure patch logic, runs on any PC).

## Not verified yet
* The startup crash fix has not yet been verified on a phone.
* `mprotect` on the game's code pages may be refused on some devices.
* Offsets are for ONE Minecraft build. A signature scan is the next step to survive updates.
