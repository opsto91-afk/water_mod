# AnalyzePatchSites.py -- Ghidra headless postScript for the water_mod patch sites.
#
# What it does:
#   For each of the 7 patch/guard sites in libminecraftpe.so it prints
#     - the instruction at the site and a few before/after it
#     - the name + entry of the containing function (Neon-style demangled if available)
#     - the FULL decompiled C pseudocode of that function (via DecompInterface)
#
# Run (from the repository root, Ghidra installed, libminecraftpe.so imported):
#   $GHIDRA_HOME/support/analyzeHeadless /tmp/ghproj watermod \
#       -import /path/to/libminecraftpe.so \
#       -scriptPath tools/ghidra \
#       -postScript AnalyzePatchSites.py \
#       -deleteProject
#
# Output is written to stdout -- redirect it to a file if you want to keep it:
#   ... -deleteProject 2>&1 | tee patch_sites_report.txt
#
# Notes:
#   - Needs no code changes in Ghidra; works on Ghidra 11.x/12.x with Jython.
#   - Do NOT copy libminecraftpe.so into the repository (proprietary). Import it
#     from wherever you keep it on your own disk.

from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor

SITES = [
    ("guard  mov w1,#5",           0x1088d3e8),
    ("water_delay (a) mov w26,#5",0x1088d3ec),
    ("guard  mov w1,#5",           0x1088d7b8),
    ("water_delay (b) mov w26,#5",0x1088d7bc),
    ("lava overworld  mov w8,#30",0x1088d7ec),
    ("lava nether     mov w9,#10",0x1088d7f4),
    ("sources         cmp w8,#2", 0x1088d990),
]

CTX = 6  # instructions of context before / after each site


def run():
    prog = currentProgram
    space = prog.getAddressFactory().getDefaultAddressSpace()
    fm = prog.getFunctionManager()
    listing = prog.getListing()
    monitor = ConsoleTaskMonitor()

    decomp = DecompInterface()
    decomp.openProgram(prog)

    def addr(off):
        return space.getAddress(off)

    for name, off in SITES:
        print("")
        print("=" * 72)
        print("%s @ 0x%x" % (name, off))
        a = addr(off)

        # ---- context disassembly ----
        window = []
        cur = listing.getInstructionAt(a)
        back = []
        p = listing.getInstructionBefore(a)
        for _ in range(CTX):
            if p is None:
                break
            back.append(p)
            p = listing.getInstructionBefore(p.getAddress())
        back.reverse()
        cur_list = back + ([cur] if cur else [])
        nxt = listing.getInstructionAfter(a) if cur else None
        for _ in range(CTX):
            if nxt is None:
                break
            cur_list.append(nxt)
            nxt = listing.getInstructionAfter(nxt.getAddress())
        print("-- context (site marked)")
        for ins in cur_list:
            mark = "  <== SITE" if ins.getAddress() == a else ""
            print("  0x%x:\t%s%s" % (ins.getAddress().getOffset(), ins, mark))

        # ---- containing function ----
        func = fm.getFunctionContaining(a)
        if func is None:
            print("!! no function contains this address -- wrong binary/build?")
            continue
        print("-- function: %s  entry=0x%x  size=%d" % (
            func.getName(), func.getEntryPoint().getOffset(), func.getBody().getNumAddresses()))

        # ---- decompiled pseudocode ----
        res = decomp.decompileFunction(func, 180, monitor)
        if res and res.decompileCompleted():
            c = res.getDecompiledFunction().getC()
            print("-- decompiled C:")
            print(c)
        else:
            msg = res.getErrorMessage() if res else "no result"
            print("!! decompile failed: %s" % msg)

    decomp.dispose()
    print("")
    print("AnalyzePatchSites: DONE")


run()
