#!/usr/bin/env python3
"""Hunt the "water spread cap" instruction near the liquid tick worker.

Context
-------
The water/lava patches of Water Tweaks live in the main liquid tick worker
(0x1088d268 in the analysed build). The "water stops after N blocks" behaviour
is controlled by a small immediate somewhere in the same area (or in a function
it calls): a compare (cmp/subs) or a move (mov/movz) against the max flow
distance, or a decrement of the fluid "level".

This tool disassembles the region with Capstone and prints every instruction
whose immediate could be that cap, so the right site can be picked from real
bytes instead of guessed.

Usage:
    # ELF mode (whole library):
    python3 tools/find_flow_sites.py /path/to/libminecraftpe.so

    # RAW slice mode (small dump covering the liquid code), e.g.:
    python3 -c "open('slice.bin','wb').write(open('libminecraftpe.so','rb').read()[0x1088c000:0x1088e200])"
    python3 tools/find_flow_sites.py slice.bin --raw --raw-base 0x1088c000

    # Tune the window (defaults shown) or dump everything:
    python3 tools/find_flow_sites.py slice.bin --raw --raw-base 0x1088c000 \
        --start 0x1088d268 --end 0x1088e200 [--all]

Tags printed per hit:
    IMMMOV   mov/movz/movk with immediate 1..16        (candidate: cap / spread distance)
    CMP      cmp/subs/adds with immediate 1..16        (candidate: level check)
    DEC      sub/add with immediate 1                  (candidate: fluid level decrement)
    SEVEN    any instruction mentioning 7 or 8         (vanilla overworld water distance)

Needs: pip install capstone pyelftools
"""
import argparse
import os
import re
import struct
import sys

try:
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
except ImportError as e:
    sys.exit("Missing dependency: %s\n  pip install capstone" % e)

try:
    from elftools.elf.elffile import ELFFile
    HAVE_ELF = True
except ImportError:
    HAVE_ELF = False


def build_vaddr_map(elf):
    segs = []
    for seg in elf.iter_segments():
        if seg.header.p_type != "PT_LOAD":
            continue
        flags = seg.header.p_flags
        segs.append((seg.header.p_vaddr,
                     seg.header.p_vaddr + seg.header.p_filesz,
                     seg.header.p_offset,
                     bool(flags & 1)))
    return segs


def read_at(path, off, size):
    with open(path, "rb") as f:
        f.seek(off)
        return f.read(size)


def imm_of(op_str):
    m = re.findall(r'#(-?0x[0-9a-fA-F]+|-?\d+)', op_str)
    vals = []
    for tok in m:
        try:
            vals.append(int(tok, 0))
        except ValueError:
            pass
    return vals


def classify(ins):
    mn = ins.mnemonic.lower()
    ops = ins.op_str
    imms = imm_of(ops)
    small = [v for v in imms if 1 <= v <= 16]
    tags = []
    if mn.startswith(("mov", "movz", "movk", "movn")) and small:
        tags.append("IMMMOV")
    if mn in ("cmp", "subs", "adds", "ccmn", "ccmp", "cmn", "tst") and small:
        tags.append("CMP")
    if mn in ("sub", "add") and any(v == 1 for v in imms):
        tags.append("DEC")
    if any(v in (7, 8) for v in imms):
        tags.append("SEVEN")
    return tags


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("so")
    ap.add_argument("--raw", action="store_true")
    ap.add_argument("--raw-base", type=lambda x: int(x, 0), default=0)
    ap.add_argument("--start", type=lambda x: int(x, 0), default=0x1088d268)
    ap.add_argument("--end", type=lambda x: int(x, 0), default=0x1088e200)
    ap.add_argument("--all", action="store_true", help="dump every instruction, not just hits")
    args = ap.parse_args()

    raw_base = args.raw_base
    if args.raw:
        raw_size = os.path.getsize(args.so)
        segs = [(raw_base, raw_base + raw_size, 0)]
        def v2o(vaddr, size):
            off = vaddr - raw_base
            return off if 0 <= off and off + size <= raw_size else None
        print("RAW: %s size=0x%x base=0x%x" % (args.so, raw_size, raw_base))
    else:
        if not HAVE_ELF:
            sys.exit("pyelftools needed for ELF mode: pip install pyelftools  (or use --raw)")
        with open(args.so, "rb") as f:
            elf = ELFFile(f)
            if elf.header.e_machine != "EM_AARCH64":
                print("!! WARNING: not an AArch64 ELF")
            segs = build_vaddr_map(elf)
        print("ELF: %s  PT_LOAD:" % args.so)
        for lo, hi, off, exe in segs:
            print("  0x%09x-0x%09x file+0x%x exec=%s" % (lo, hi, off, exe))
        def v2o(vaddr, size):
            for lo, hi, off, exe in segs:
                if lo <= vaddr and vaddr + size <= hi:
                    return off + (vaddr - lo)
            return None

    start, end = args.start, args.end
    print("scanning window 0x%x..0x%x" % (start, end))
    blob = bytearray()
    cur = start
    # read in one go if contiguous
    off = v2o(start, end - start)
    if off is None:
        sys.exit("FAIL: window [0x%x, 0x%x) not fully mapped (use a bigger slice / different --start --end)" % (start, end))
    blob = read_at(args.so, off, end - start)

    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    hits = 0
    total = 0
    for ins in md.disasm(blob, start):
        total += 1
        tags = classify(ins)
        if args.all or tags:
            tag = ",".join(tags) if tags else ""
            if tags or args.all:
                print("0x%08x: %-28s %-30s %s" % (ins.address, ins.mnemonic, ins.op_str, tag))
            if tags:
                hits += 1
    print("\ninstructions disassembled: %d | flagged: %d" % (total, hits))
    print("next step: pick the cap site from the flagged instructions by looking at the")
    print("surrounding context (--all), then add it as a verified patch site with guards.")


if __name__ == "__main__":
    main()
