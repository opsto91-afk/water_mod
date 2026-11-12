# FindSpreadCap.py -- Ghidra headless postScript: locate the instruction that
# stops water from spreading ("reach cap").
#
# Run (repo root, after `git pull`):
#   $GHIDRA_HOME/support/analyzeHeadless /tmp/ghproj flowcap \
#       -import /path/to/libminecraftpe.so \
#       -scriptPath tools/ghidra \
#       -postScript FindSpreadCap.py \
#       -deleteProject 2>&1 | tee /tmp/flowcap_report.txt
#
# What it prints:
#   A) the 3 anchors (liquid tick worker + the two water-delay sites): which function owns them, entry, size
#   B) every instruction in the scan window whose operand is a small immediate (0..16), tagged
#      IMMMOV / CMP / DEC / SEVEN -- this is the candidate list for the spread cap
#   C) full disassembly of each containing function
#   D) decompiled C of each containing function (the cap instruction will be visible there)
#
# Needs the real libminecraftpe.so (or the slice that contains the worker). The default scan
# window covers 0x1088c000..0x1088e200 (the liquid tick worker region we know).

from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor
from ghidra.program.model import Scalar, Register

ANCHORS = [0x1088d268, 0x1088d3ec, 0x1088d7bc]
SCAN_START = 0x1088c000
SCAN_END = 0x1088e200

WINDOWS = [(SCAN_START, SCAN_END)]


def run():
    prog = currentProgram
    space = prog.getAddressFactory().getDefaultAddressSpace()
    fm = prog.getFunctionManager()
    listing = prog.getListing()
    monitor = ConsoleTaskMonitor()

    def addr(off):
        return space.getAddress(off)

    def imm_values(ins):
        vals = []
        for i in range(ins.getNumOperands()):
            for o in ins.getOpObjects(i):
                if isinstance(o, Scalar):
                    vals.append(o.getValue() & 0xFFFFFFFFFFFFFFFF)
        return vals

    print("=" * 78)
    print("A) ANCHORS")
    anchor_funcs = []
    for a in ANCHORS:
        func = fm.getFunctionContaining(addr(a))
        if func is None:
            print("0x%x: NO CONTAINING FUNCTION (wrong build / not analysed?)" % a)
            continue
        ent = func.getEntryPoint().getOffset()
        sz = func.getBody().getNumAddresses()
        print("0x%x: in function %s  entry=0x%x size=%d" % (a, func.getName(), ent, sz))
        if all(func.getEntryPoint() != f.getEntryPoint() for f in anchor_funcs):
            anchor_funcs.append(func)

    print("")
    print("=" * 78)
    print("B) SMALL-IMMEDIATE CANDIDATES in 0x%x..0x%x" % (SCAN_START, SCAN_END))
    hits = 0
    inst = listing.getInstructionAt(addr(SCAN_START))
    while inst is not None and inst.getAddress().getOffset() < SCAN_END:
        mn = inst.getMnemonicString().lower()
        vals = imm_values(inst)
        small = [v for v in vals if v <= 16]
        tags = []
        if mn.startswith(("mov", "movz", "movn", "movk")) and small:
            tags.append("IMMMOV")
        if mn in ("cmp", "subs", "adds", "cmn", "tst", "ccmp", "ccmn") and small:
            tags.append("CMP")
        if mn in ("sub", "add") and any(v == 1 for v in vals):
            tags.append("DEC")
        if any(v in (7, 8) for v in vals):
            tags.append("SEVEN")
        if tags:
            hits += 1
            print("0x%x:\t%-8s %-40s %s" % (inst.getAddress().getOffset(), mn.upper(), inst.toString(), ",".join(tags)))
        inst = listing.getInstructionAfter(inst.getAddress())
    print("candidates: %d" % hits)

    print("")
    print("=" * 78)
    print("C) FULL DISASSEMBLY of containing function(s)")
    for func in anchor_funcs:
        print("\n---- %s 0x%x ----" % (func.getName(), func.getEntryPoint().getOffset()))
        body = func.getBody()
        it = listing.getInstructions(body, True)
        for ins in it:
            print("0x%x:\t%s" % (ins.getAddress().getOffset(), ins.toString()))

    print("")
    print("=" * 78)
    print("D) DECOMPILED C of containing function(s)")
    decomp = DecompInterface()
    decomp.openProgram(prog)
    for func in anchor_funcs:
        print("\n---- %s 0x%x ----" % (func.getName(), func.getEntryPoint().getOffset()))
        res = decomp.decompileFunction(func, 240, monitor)
        if res and res.decompileCompleted():
            print(res.getDecompiledFunction().getC())
        else:
            print("!! decompile failed")
    decomp.dispose()

    print("")
    print("FindSpreadCap: DONE")


run()
