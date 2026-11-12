# Ghidra analysis of libminecraftpe.so patch sites (run on YOUR machine)

This sandbox cannot download Ghidra (release-assets host is not reachable from
it), so the heavy analysis is packaged here to run anywhere — your PC, or the
GitHub Codespace — where outbound internet is open.

## 1. Install Ghidra 12.2 (~570 MB) + JDK 21

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk-headless unzip curl   # or: default-jdk-headless

curl -L -o /tmp/ghidra.zip \
  https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.2_build/ghidra_12.2_PUBLIC_20261016.zip
unzip -q /tmp/ghidra.zip -d "$HOME/ghidra"
export GHIDRA_HOME="$HOME/ghidra/ghidra_12.2_PUBLIC"
```

Check: `"$GHIDRA_HOME/support/analyzeHeadless" -help | head -1`

## 2. Put libminecraftpe.so somewhere on disk

Do **not** copy it into this repository — it is proprietary Mojang code.
Keep it at e.g. `$HOME/libminecraftpe.so`.

Quick sanity check with the repo tools (no Ghidra needed):

```bash
python3 -m pip install capstone pyelftools
python3 tools/verify_offsets.py $HOME/libminecraftpe.so
```

## 3. Run the headless analyzer

```bash
cd /path/to/water_mod   # repository root
"$GHIDRA_HOME/support/analyzeHeadless" /tmp/ghproj watermod \
    -import "$HOME/libminecraftpe.so" \
    -scriptPath tools/ghidra \
    -postScript AnalyzePatchSites.py \
    -deleteProject 2>&1 | tee patch_sites_report.txt
```

`analyzeHeadless` first imports the binary and runs auto-analysis (this is the
slow part: typically 5–30 min for ~200 MB, depending on CPU and RAM — give it
at least 8 GB free). The postScript then prints, for each of the 7 sites:
the instruction context, the containing function, and its decompiled C code.

## 4. Paste output back

Attach or paste `patch_sites_report.txt` (or just the sections around the 7
sites) into the chat. With that we can confirm whether

- the addresses really are the water/lava liquid-tick constants,
- `0x1088d3e8` / `0x1088d7b8` are guard moves feeding a keybind/liquid check,
- the mod's replacement constants (5 / 30 / 10 / 2) do what the name claims.

## Slice-only check (no Ghidra)

If sharing the full library is a problem, verify the 7 sites with a 4 KB slice:

```bash
python3 -c "open('slice.bin','wb').write(open('$HOME/libminecraftpe.so','rb').read()[0x1088d000:0x1088e000])"
python3 tools/verify_offsets.py slice.bin --raw --raw-base 0x1088d000
```
