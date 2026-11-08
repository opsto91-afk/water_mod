#!/usr/bin/env python3
"""ELF-aware verification of the water_mod patch sites in libminecraftpe.so.

Unlike tools/check_so.py (which only does file offset == Zaphkiel address), this script
maps ELF VIRTUAL ADDRESSES to FILE OFFSETS via the program headers, checks that every site
lives in an executable PT_LOAD segment, reads the 4 bytes there, and disassembles the site
plus its surroundings with Capstone so you can eyeball that the instruction really is what
the patch table claims (mov w26,#5 / mov w8,#30 / ...).

Usage:
    python3 tools/verify_offsets.py /path/to/libminecraftpe.so [--context 12]
    python3 tools/verify_offsets.py /path/to/slice.bin --raw [--raw-base 0x1088d000] [--context 12]

--raw treats the file as a flat binary dump of the range starting at --raw-base
(default 0). Use it when only a few KB around the patch sites are available
(e.g. dd if=libminecraftpe.so of=slice.bin bs=4096 skip=$((0x1088d000/4096)) count=1).

Exit code 0 = every guard + patch site matches; non-zero = mismatch / not mapped.
Needs: pip install capstone pyelftools
"""
import argparse
import os
import struct
import sys

try:
    from elftools.elf.elffile import ELFFile
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
    from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG
    import capstone.arm64 as arm64
except ImportError as e:
    sys.exit("Missing dependency: %s\n  pip install capstone pyelftools" % e)

# (name, offset == Zaphkiel vaddr, expected little-endian word)
SITES = [
    ("guard  mov w1,#5",           0x1088d3e8, 0x528000A1),
    ("guard  mov w1,#5",           0x1088d7b8, 0x528000A1),
    ("water_delay (a) mov w26,#5", 0x1088d3ec, 0x528000BA),
    ("water_delay (b) mov w26,#5", 0x1088d7bc, 0x528000BA),
    ("lava overworld  mov w8,#30", 0x1088d7ec, 0x528003C8),
    ("lava nether     mov w9,#10", 0x1088d7f4, 0x52800149),
    ("sources         cmp w8,#2",  0x1088d990, 0x7100091F),
]

PATCH_NAMES = {0x1088d3ec, 0x1088d7bc, 0x1088d7ec, 0x1088d7f4, 0x1088d990}


def build_vaddr_map(elf):
    """Return list of (vaddr_lo, vaddr_hi, file_off, flags_str, executable) for PT_LOAD segments."""
    segs = []
    for seg in elf.iter_segments():
        if seg.header.p_type != "PT_LOAD":
            continue
        flags = seg.header.p_flags  # PF_X=1 PF_W=2 PF_R=4
        segs.append((
            seg.header.p_vaddr,
            seg.header.p_vaddr + seg.header.p_filesz,
            seg.header.p_offset,
            "".join(c for c, bit in (("X", 1), ("W", 2), ("R", 4)) if flags & bit) or "-",
            bool(flags & 1),
        ))
    return segs


def vaddr_to_fileoff(segs, vaddr, size):
    for lo, hi, off, flags, exe in segs:
        if lo <= vaddr and vaddr + size <= hi:
            return off + (vaddr - lo), flags, exe
    return None, None, None


def read_at(path, off, size):
    with open(path, "rb") as f:
        f.seek(off)
        return f.read(size)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("so")
    ap.add_argument("--context", type=int, default=8,
                    help="instructions of context to print before/after each site (default 8)")
    ap.add_argument("--raw", action="store_true",
                    help="file is a flat binary dump (no ELF parsing); use with --raw-base")
    ap.add_argument("--raw-base", type=lambda x: int(x, 0), default=0,
                    help="vaddr of the first byte in the raw dump (default 0)")
    args = ap.parse_args()

    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = False

    if args.raw:
        raw_base = args.raw_base
        raw_size = os.path.getsize(args.so)
        print("RAW: %s | size=0x%x | base=0x%x  (flat dump, no ELF checks)" % (args.so, raw_size, raw_base))
        segs = [(raw_base, raw_base + raw_size, 0, "R-X?", True)]
        # synthetic map: vaddr -> offset = vaddr - raw_base
        def raw_vaddr_to_fileoff(segs_, vaddr, size):
            off = vaddr - raw_base
            if off < 0 or off + size > raw_size:
                return None, None, None
            return off, "R-X?", True
        run_sites(args, segs, read_at, raw_vaddr_to_fileoff, md)
        return

    with open(args.so, "rb") as f:
        elf = ELFFile(f)
        machine = elf.header.e_machine
        bits = elf.elfclass
        print("ELF: %s | machine=%s | class=%s" % (args.so, machine, bits))
        if machine != "EM_AARCH64":
            print("!! WARNING: not an AArch64 ELF -- offsets were taken from an arm64 build; results are meaningless.")
        print("entry=0x%x" % elf.header.e_entry)
        segs = build_vaddr_map(elf)

    first_load = min(s[0] for s in segs) if segs else None
    print("PT_LOAD segments:")
    for lo, hi, off, flags, exe in segs:
        print("  vaddr 0x%09x-0x%09x  file+0x%09x  flags=%s  exec=%s  (%.1f MB)" % (
            lo, hi, off, flags, exe, (hi - lo) / 1048576.0))
    print("first PT_LOAD vaddr = 0x%x  (the mod assumes this is 0)" % first_load)
    if first_load != 0:
        print("!! WARNING: first LOAD segment does not start at vaddr 0 -- the mod's base+offset assumption is WRONG for this file.")
        print("   The mod would patch the wrong addresses. Use vaddr-based mapping.")

    run_sites(args, segs, read_at, vaddr_to_fileoff, md)


def run_sites(args, segs, read_at, vaddr_to_fileoff, md):

    all_ok = True
    for name, want_vaddr, want_word in SITES:
        foff, flags, exe = vaddr_to_fileoff(segs, want_vaddr, 4)
        print("\n--- %s @ 0x%x" % (name, want_vaddr))
        if foff is None:
            print("  FAIL: vaddr not covered (raw file too small / wrong --raw-base?)")
            all_ok = False
            continue
        raw = read_at(args.so, foff, 4)
        if len(raw) != 4:
            print("  FAIL: short read")
            all_ok = False
            continue
        got = struct.unpack("<I", raw)[0]
        ok = got == want_word
        all_ok &= ok
        print("  file offset : 0x%x  flags=%s exec=%s" % (foff, flags, exe))
        print("  bytes found : %s (0x%08x)" % (" ".join("%02X" % b for b in raw), got))
        print("  bytes wanted: %s (0x%08x)  -> %s" % (
            " ".join("%02X" % b for b in struct.pack("<I", want_word)), want_word, "OK" if ok else "MISMATCH"))
        if not exe:
            print("  !! site is NOT in an executable segment")
            all_ok = False
        for ins in md.disasm(raw, want_vaddr):
            print("  insn        : 0x%x:\t%s\t%s" % (ins.address, ins.mnemonic, ins.op_str))
        ctx = args.context
        start = max(0, want_vaddr - 4 * ctx)
        size = 4 * (2 * ctx + 1)
        coff, _, _ = vaddr_to_fileoff(segs, start, size)
        if coff is not None:
            blob = read_at(args.so, coff, size)
            print("  context (±%d insns):" % ctx)
            for ins in md.disasm(blob, start):
                marker = "  <== SITE" if ins.address == want_vaddr else ""
                print("    0x%08x:\t%-8s %s%s" % (ins.address, ins.mnemonic, ins.op_str, marker))

    print("\n=========================================")
    if all_ok:
        print("ALL OK - this build matches the patch table.")
        sys.exit(0)
    print("MISMATCH - different Minecraft build or wrong binary; do not use these offsets.")
    sys.exit(1)


if __name__ == "__main__":
    sys.exit(main())
