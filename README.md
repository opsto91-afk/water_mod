# Water Tweaks v0.2 (LeviLauncher native mod)

Changes how fast water/lava update and how many sources make a new source, by patching 5 ARM64
instructions in `libminecraftpe.so` (found with Zaphkiel in the main liquid tick worker `0x1088d268`).

## Before building (PC / Codespace)
    python3 tools/check_so.py /path/to/libminecraftpe.so     # every line must say OK

## Build
Run the repository's **Water Mod Build** workflow in GitHub Actions (or push a change).
When it succeeds, download the `water_mod` artifact and import `water_mod.levipack`
in LeviLauncher.

To build locally, install the Android NDK and run:

    ANDROID_NDK_HOME=/path/to/ndk ./build.sh

## Use (v0.2: Mod Menu sliders)
The module is registered about 3 seconds after the mod is enabled. Then use
Mod Menu -> "Water Tweaks": toggle + sliders (water delay, lava delay overworld / nether, sources for infinite water)
+ "Dry run" toggle. Values are saved to water.cfg. Slider changes are applied ~0.3 s later.

### First test (crash hunting) - do it in this order
1. Turn **Dry run** ON, then the module ON -> log must say `verify OK ... DRY RUN`. Nothing is written.
2. Dry run OFF -> log says `writing patches ...` and `patched 5 instruction(s)`.
3. Open `Android/media/org.levimc.launcher/kurumi/water_mod.log` - every step is flushed to disk, so even after a
   crash the last line shows how far it got. (Preloader's own log goes to Android logcat, not to the launcher.)
3b. Crash tracer: on SIGSEGV/SIGBUS/SIGILL/SIGABRT/SIGFPE the log gets a `*** CRASH` block with fault address,
   pc and lr as `libminecraftpe.so+0x...` / `water_mod.so+0x...` and the `stage` we were in. Send that block.
4. If the previous run died after patching, the next start logs `SAFE MODE` and writes nothing until you delete
   `water_mod.disabled` in the mod's data dir.

## Old text file way (still read at start)
1. Start the game once, edit `water.cfg` in the mod's data folder (created on first load):
       water_delay=5            # ticks, vanilla 5   (1..2000)
       lava_delay_overworld=30  # vanilla 30
       lava_delay_nether=10     # vanilla 10
       infinite_source_count=2  # vanilla 2          (1..8)
2. Mod Menu -> "Water Tweaks" -> ON. Edit the file -> OFF, ON to re-apply.
3. The mod log tells what happened ("patched 5 instruction(s)", or "NOT patching: ...").

## Safety
* Each site is checked against the exact vanilla instruction first. Different Minecraft build =>
  nothing is written ("NOT patching: guard mismatch ...").
* OFF restores the vanilla instructions.
* Local / single-player worlds only: liquid ticks run on the server side.

## Tests
`make -C tests run` (pure patch logic, runs on any PC).

## Not verified yet
* The package compiles against the Preloader SDK, but the delayed registration and crash tracer still need
  verification on a phone.
* `mprotect` on the game's code pages may be refused on some devices.
* Offsets are for ONE Minecraft build. A signature scan is the next step to survive updates.
