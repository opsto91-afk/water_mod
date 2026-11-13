# BedrockTools Freelook — reverse engineering notes (2026-11-11)

Source of truth: user's own `libBedrockTools.so` (MT Manager export `libBedrockTools(Copy).so.txt`,
1,958,616 B, EM_AARCH64, 27 sections). Ghidra pseudo-C (`all_functions.c`) + capstone disasm.
Target game: MC 1.26.52 ("60.4"), `libminecraftpe.so` 342,231,392 B (identity-mapped text LOAD, vaddr==fo).

## TL;DR

- BedrockTools freelook = **3 inline hooks + 2 direct calls** on MC functions, chosen by **AOB signature**.
- The signature table lives in `.data.rel.ro` (152 entries x 48 B at va `0x1d92b0`), patched by
  `.rela.dyn` R_AARCH64_RELATIVE entries. Layout per entry:
  `u64 namePtr (relocated), u64 nameLen, u64 B_ptr, u64 B_len, u64 uVar17, u16 sigId` (+ flags).
- `resolve(sigId)` returns `DAT_002e9a38[sigId]` (local `bedrocktools::memory::resolve`,
  `bl 0x1d12b0` → GOT → local `resolve@0xb3634`), filled by local `resolveAll`:
  AOB path (`find` in a `pl::memory::resolveSignatures` map, li = `span<string>` of non-empty
  patterns) or vtable path (`pl::memory::resolveVtableFunction`). Both are extern (PLT).
- **Freelook "camera" signature (sigId 0x8b) is EMPTY in this build** (`Alen=0`, no B) →
  `resolve(0x8b) == 0` → `FreelookModule::onInit` logs
  `Freelook signatures missing: camera={}, ...` and **uninstalls nothing / never hooks**.
  Statically, freelook cannot run on this build of the mod. See "Open questions".

## The 5 freelook signatures (attributed)

Log arg order in onInit == resolve call order:
camera←`resolve(0x8b)` → local_48, turn←`resolve(0x3e)` → local_98, perspective←`resolve(0x19)` → local_a0,
headGet←`resolve(0x8c)` → `DAT_002ead20`, headSet←`resolve(0x8d)` → `DAT_002ead28`.

| name        | sigId | table entry | sig-string va/len | hook? | AOB signature |
|-------------|-------|-------------|-------------------|-------|----------------|
| camera      | 0x8b  | #138        | empty (len 0)     | hooked (detour1 @0x183188) | **EMPTY — unresolved in this build** |
| turn        | 0x3e  | #61         | 0x68100 (77)      | hooked (detour2 @0x18359c) | `? ? ? F9 ? ? ? B9 ? ? ? 29 ? ? ? F9 E1 03 08 2A 80 00 1F D6 ? ? ? D1 ? ? ? A9` |
| perspective | 0x19  | #24         | 0x71ca7 (77)      | hooked (detour3 @0x183894) | `? ? ? FC ? ? ? 6D ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? 91 08 40 20 1E F4 03 01 2A` |
| headGet     | 0x8c  | #139        | 0x69c03 (83)      | called directly | `? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? A9 ? ? ? 91 54 D0 3B D5 ? ? ? 52` |
| headSet     | 0x8d  | #140        | 0x70402 (413)     | called directly | `? ? ? F9 ? ? ? 52 00 E4 00 2F ? ? ? 72 ? ? ? A9 08 01 0B CB ? ? ? D3 ? ? ? 51 0C 01 0A 8A ? ? ? B9 6B 0D 0C 8B ? ? ? F9 ? ? ? F9 ? ? ? B1 ? ? ? 54 8B 15 0B 8B ? ? ? B9 BF 01 0A 6B ? ? ? 54 ? ? ? F9 7F 01 09 EB ? ? ? 54 ? ? ? F9 ? ? ? B4 ? ? ? A9 ? ? ? D3 8C 01 0A CB 7F 0D 8C EB ? ? ? 54 ? ? ? F8 ? ? ? B4 ? ? ? 92 ? ? ? 12 ? ? ? 92 ? ? ? B8 ? ? ? 32 48 01 08 4A 1F 01 0B 6B ? ? ? 54 ? ? ? D3 ? ? ? F9 01 E4 00 2F` |

### Cross-match against our v1.2 sigs (device-proven on MC 1.26.52, log 2026-11-10)

| our v1.2 sig | == BedrockTools sigId | note |
|---|---|---|
| `UpdatePlayerFromCamera` | **0x8c (headGet)** | hooked by us; fires every frame |
| `LocalPlayerApplyTurnDelta` | **0x3f** | NOT freelook's turn (0x3e) |
| `RenderLevel` | **0x23** | NOT used by freelook |

→ v1.2 failed because none of its hooks are the freelook camera path.

## Mechanism (detours)

All three detours: recursive-mutex @0x1eacc8, then gate on
global `[0x1ea0cf0]` (module state) + `[0x1ea0d18]/[0x1ea0d10]/[0x1ea0d08]` (hook regs) +
`[0x1ea0d20]/[0x1ea0d28]` (headGet/headSet ptrs) + this-module `requested/active/keybind` bytes @+0x3c/3d/3e,
plus `keybindBlocked()` (`bl 0x1d1660`) and a `tbz w0` on `bl 0x1d22a0`.
`[0x1ea0d30]/[0x1ea0d38]/[0x1ea0d40]` = the 200-byte inline-hook trampolines (original code, callable).

- **detour1 (camera)** `0x183188`: args `(x19=p0, x21=p1, x20=p2)`. If `x20` != `clientInstance->get(0x1b)->call()`
  (i.e. not the active player camera) → call original only. Else, if freelook active: if camera matches stored
  `[0x1ea0d48]/+0x1f0/+0x1e0`: write saved 16 B head pose (`q0@[0x1ea0d68]`) into `*(camera+0x238)`,
  call `headSet(s0=[0x1ea0d78].lo, s1=[0x1ea0d78].hi, camera)`; then via entt hash `0x18b1887` pool lookup
  (`bl 0x184060`) update component `+0x54/+0x5c/+0x60` (`0x600` head-bits cleared/restored depending on branch).
  Always calls original camera fn at the end; caches last camera into `0x1eada0` struct.
- **detour2 (turn)** `0x18359c` (args `(x19, x20)`): same gate; if active: pre-apply pose+headSet, call
  original turn fn (`[0x1ea0d38]`), apply again post-call, then entt `0x600`-bit write. If not active:
  plain passthrough of original.
- **detour3 (perspective)** `0x183894` (arg `x0`): calls ORIGINAL first, captures `w0`; if freelook active and
  `ensure-state(x20-cam)==1` → returns **1** instead of the original value. A bool-forcing hook
  (third-person flag). This is the "snap to third-person on activation".
- `FUN_00283e10` (0x183e10): "ensure freelook state" — `clientInstance->get(0x1b)` vs hooked camera arg;
  resolves/stashes camera into `0x1ead48`, `+0x1f0`/`+0x1e0` ids, head-pose qword from camera+0x238,
  `headGet(camera)` → `_DAT_002ead78`; entt hash `0x18b1887` lookup → stash component `+0x54/+0x5c/+0x60`
  (saves low 40 bits of u64@+0x54 in `DAT_002ead90`, u32@+0x5c in `DAT_002ead98`, bits `0x600` in `DAT_002ead9e`).
- `FUN_00284060` (0x184060): entt pool lookup for hash `0x18b1887` (`this+0x10`→`+0x38`/`+0x40/+0x50`,
  hash-map find `puVar1[1]==0x18b1887`, then `component = pool_base + (id&0x7ff)*4` lookup + `((id>>7)&0x7ff)*0x20`).
- On toggle/rising-edge, two mirror structs `0x1eacc8/0x1ead48` and `0x1eada0` are zeroed/restored.
  Restore path sets component `+0x60 |= saved 0x600`; freelook path clears `0x600`.

## Signature table internals (offsets)

- `.data.rel.ro` va `0x1d91f0` fo `0x1d11f0` (delta +0x8000). Table at va `0x1d92b0`, 152 entries × 0x30 B,
  ends va `0x1daf90`. `.bss` va `0x1e96d0` (delta fo +0xc008). `resolve()` → `adrp 0x1e9000 + 0xa38` = `0x1ea0a38` (152×8 B).
- `pl::memory::resolveSignatures` / `resolveVtableFunction` are extern (PLT); the local find() builds
  `unordered_map<string,u64>` keyed by pattern; empty A strings are skipped (camera never loads).
- vtable-sig example in table: sigId `0x84` → class `"16PlayerListPacket"` (RTTI length-prefix form), slot 17.

## Decoded sig instruction shapes (ARM64, wildcards zeroed)

- headGet 0x8c: `sub sp` / `stp x29,x30` / … / `mrs x20, tpidr_el0` (0xD53BD054) — stack-protector prologue.
- headSet 0x8d: `movi d0,#0` (0x2F000E00), `sub x8,x8,x11` (0xCB0B0108), `and x12,x8,x10` (0x8A0A010C),
  `add x11,x11,x12,lsl#3` (0x8B0C0D6B), `add x11,x12,x11,lsl#5` (0x8B0B158B) — inlined **entt pool lookup
  with 32-byte pool stride**; ends in `? ? ? F9` (str). So headSet == the inlined pool-lookup+head-struct writer.
- 0x8e (unlabeled): same body, ends `C0 03 5F D6` = `ret`.
- perspective 0x19: `fmov s8, s0` (0x1E204008) + `mov w20, w1` (0x2A0103F4).
- turn 0x3e: `mov w1, w8` (0x2A0803E1) + tail `br x4` (0xD61F0080).
- Entry #142 (0x8f): `mov x0, x26` (0xAA1A03E0) `ret` (0xD61F0000-ish 0xD63F0000? no: `00 01 3F D6` = 0xD61F0000? decoded `b`/`ret`) — tiny tail stub.
- UpdatePlayerFromCamera/LocalPlayerApplyTurnDelta/RenderLevel == 0x8c / 0x3f / 0x23 (all stack-protector prologues, MRS tpidr_el0 family).

## Open questions for the user

1. **Is freelook actually working on your device right now?** This .so statically cannot run it
   (camera sig empty). If it works, the *installed* libBedrockTools.so differs from the upload —
   please export the installed one (`/data/data/org.levimc.launcher/files/.../libBedrockTools.so`, ~1.9 MB)
   and we re-extract the 5 sigs from it. Also check the launcher log for
   `Freelook signatures missing: camera=0x0`.
2. The 3 AOB sigs (0x3e/0x19/0x8c/0x8d) still **match** the MC .so the user runs (our v1.2 hooks on
   0x3f/0x8c/0x23 resolved on device), so if you ever fill camera's entry, the hook should work on 1.26.52 —
   camera sig needs to be recreated for the current build (it was deliberately emptied upstream).
3. To attribute the sigs to concrete MC functions, the full `libminecraftpe.so` must be scanned.
   It already exists in the user's Codespaces (`/workspaces/Mods/libminecraftpe.so` or wherever the
   terminal history put it, ~342 MB). Run the scan there (script below) or on device through the
   mod's existing sig-resolver (ours logs resolved hooks but not addresses).
4. v1.3 design candidates (NOT building yet): replicate the BedrockTools mechanism —
   hook camera-writer + turn-delta + perspective-bool, keep a saved head pose + entt `0x18b1887`
   `+0x54/+0x5c/+0x60` state. Requires a camera sig; needs your answers to (1)-(3).

## Codespaces scan script (run with python3 there)

```python
import sys
path, sigs_file = sys.argv[1], sys.argv[2]
data = open(path, 'rb').read()
print('size', hex(len(data)))
for name, s in ((l.split('=')[0].strip(), l.split('=',1)[1].strip()) for l in open(sigs_file) if l.strip()):
    toks = s.split(); pat = [(0 if t=='?' else int(t,16), t!='?') for t in toks]
    # anchor scan on longest exact chunk
    chunks=[]; cur=bytearray(); base=None
    for k,(b,m) in enumerate(pat):
        if m:
            if base is None: base=k
            cur.append(b)
        else:
            if cur: chunks.append((base,bytes(cur)))
            cur=bytearray(); base=None
    if cur: chunks.append((base,bytes(cur)))
    off,anchor = max(chunks, key=lambda c: len(c[1]))
    hits=[]
    i=0
    while True:
        i=data.find(anchor,i)
        if i<0: break
        st=i-off
        if 0<=st<=len(data)-len(pat):
            if all(data[st+o:st+o+len(c)]==c for o,c in chunks): hits.append(st)
        i+=4
    print(name, 'matches:', [hex(h) for h in hits])
```

---

## Phase 2 (2026-11-11) — Scan hits & function-level decode

Codespaces scan of `libminecraftpe.so` (file size 0x14660960) found the
BedrockTools signatures at:

| sig | addr | file scope | note |
|---|---|---|---|
| turn 0x3e | 0x1037352c | seg0 (identity va==fo) | 6-instr VIRTUAL-DISPATCH THUNK; real body at 0x10373544 |
| perspective 0x19 | 0xb7c8b9c | seg0 | matches sig at entry; FOV easing fn |
| headGet 0x8c | 0x8a42760 | seg0 | matches sig at entry; entt-component lookup |

BL/literal relocations identity-map seg0 → **va == fo**; zapkhiel/LeviMC
runtime addresses = these vaddrs.

### turn @ 0x1037352c (thunk) / real body @ 0x10373544
Thunk: `ldr x9,[x0]; ldr w3,[x1,#8]; ldp w8,w2,[x1]; ldr x4,[x9,#0x1b8];
mov w1,w8; br x4` — forwards to vtable slot 0x1b8 ((*(this+0))+0x1b8) with
repacked args (this, lo32(arg1), hi32(arg1), arg2).  **Hooking the thunk
intercepts vtable slot 55 dispatch, not gameplay logic per se.**
Real body (0x10373544): tpidr_el0-hardened frame, big body, vtable call chain
([x20] slots 0x110/0x118/0x130/0x150), hash `mov w8,#0xc5ac; movk w8,#0x3727,lsl#16`
(0x3727c5ac) + `dup v8.2s` (packed-pair compare), iterator loop with trip count
`x0<<3`, calls 0xfd98a68/0x109f6e88/0x109f5e38/0x11f10838 — an entt view/range
construction over a component pool (CameraComponent family by style).

### perspective @ 0xb7c8b9c — FOV easing / lerp calculator
Prologue matches sig (`str d10,[sp,#-0x40]!; stp d9,d8,[sp,#8];
stp x29,x30,[sp,#0x18]; ... fmov s8,s0; mov w20,w1; tbz w1,#0`).
Reads this+0xea8 (min FOV) / this+0xeac (max FOV) as floats, lerps with the
incoming float arg, member at +0x10f8 (vtable slots 0x568/0x438/0x130/0x618/0x338),
member at +0x1118 (object reached via member at +0x1118; call w1=0x2f virtual
@+0xe0), byte flag +0x948, writes a global flag at `adrp 0x140aa000 + 0x550`
with `ldarb/str`, clamps to [{60,69,70,130}f constants; 0x42700000=60f,
0x428c0000=70f, 0x42700000=60f, 0x43020000=130f], log-once guard
`bl #0x135d36e0` (prints a clamp warning once). Sibling function at 0xb7c8e04
(same shape, inverse direction).  **This is NOT a "perspective mode" toggle —
the name is misleading; it is the /camera ease FOV computation.**

### headGet @ 0x8a42760 — entt multi-component lookup (returns double*)
Third-generation entt pool lookup, three hash families inlined:
0x02C74480 (outer pool; bucket mask `and x9,x9,#0x02C74480`),
0x31A824ED (second pool; copies a DOUBLE out via `ldr d0,[x9,x10,lsl #3]`),
0x3EB91730 (third; iterator + virtual call loop via `blr` on [x9,#0x10]).
Fallback: 0xfd869b0 (get double*), 0xfd89a58, 0xfd98a68.
`cmn x14,#1` / `b.eq` = -1 sentinel (entt null entity).
**Not a simple pose read; it walks CameraComponent-family pools.**

### Cross-checks
- None of turn/perspective/headGet reference entt hash 0x18b1887 (camera
  component hash from BedrockTools headSet) — that hash lives somewhere else
  (likely the real camera class methods; find it by scanning for the
  `mov w8,#0x1887 ; movk w8,#0x18b1,lsl#16` movk pair).
- v1.2's three hooks resolved "OK" on device but are NOT freelook hook points
  (v1.2 fail explained).  The 0x8c hook (UpdatePlayerFromCamera-ish via RE doc)
  was in fact this entt lookup.

### New tool: tools/re/find_class.py
Self-contained RELA+RELR dual-path vtable→class-name decoder:
scans .data*/.got for direct 8-byte refs (RELR-style) AND parses
.rela.dyn RELATIVE (R_AARCH64_RELATIVE=0x403) sites; groups sites into runs
(candidate vtables); decodes [offset-to-top=0][type_info*][slots...] runs;
type_info → member name string ("19LevelRendererCamera"-style) via reloc chain.
Validated on BedrockTools .so (2141 RELATIVE sites parsed; typeinfo-string
decode verified, e.g. '16PlayerListPacket').

### Next step
Run in Codespaces on the full .so:
    python3 tools/re/find_class.py libminecraftpe.so 0x1037352c 0xb7c8b9c 0x8a42760 0x8a3d988 0x8a3da24
(5 addrs; 0x8a3d988/0x8a3da24 = the zapkhiel-dumped entt zone which CALLS
0xb7c8b9c; any one decoding pins the camera class family.)

If all report NO refs → functions are non-virtual → fall back to Ghidra
'find references to address' (caller analysis) or zapkhiel-dump the caller zone;
camera class will surface via the class of the object whose vtable calls these.

### CameraAPI vtable (decoded 2026-11-12 via Codespaces run of find tool)
name string "9CameraAPI" @ 0x2a44130 · type_info @ 0x13791cc0 · **primary vtable @ 0x13791388**
(secondary vtables @ 0x13791d10 / 0x13791d28 / 0x13791d40 / 0x13791d70 — virtual-base secondaries)

| slot | fn | notes |
|---|---|---|
| 0 | 0x8a60e6c | `ret` stub (deleting-dtor part) |
| 1 | 0x8a60e70 | tiny stub |
| 2 | 0x8a3d078 | sub sp,#0x90 — real (candidate: per-frame pose?) |
| 3 | 0x8a3d1e8 | sub sp,#0x80 — real |
| 4 | 0x8a3d31c | sub sp,#0x50 — real |
| 5 | 0x8a3d470 | sub sp,#0x70 — real |
| 6 | **0x8a3da24** | entt camera-target resolver (doubles, component lookup); called by /camera ease path |
| 7 | **0x8a3d988** | bool-w20 branch → 0xb7c8b9c (FOV ease) / 0xb7c8e04; packs x0 = (1<<32)|bits(s0*0.017334) |
| 8 | 0x8a3d590 | real |
| 9 | 0x8a3de78 | bl out-of-range caller; real |
| 10 | 0x8a3d650 | real |
| 11 | 0x8a3de90 | real |
| 12 | 0x8a3df00 | real |
| 13 | 0x8a3d6a0 | tail-call: ldr x0,[x0]; ldr x8,[x0]; ldr x1,[x8,#0x100]; br x1 (forwarder) |
| 14 | 0x8a3d6b0 | real |
| 15 | 0x8a3d6e4 | real |
| 16 | 0x8a3d81c | real |
| 17 | 0x8a3d864 | real |
| 19/30/36/42 | 0x13791d10/0x13791d28/0x13791d40/0x13791d70 | secondary vtable ptrs |
| 20/21/32/33/38/39/43/44 | 0x8a60e7c..0x8a60f1c | tiny stubs |
| 22 | 0x8a3e1a8 | real |
| 23 | 0x8a3e374 | real |
| 24 | 0x8a3e650 | real |
| 25 | 0x8a3e7d8 | real |
| 26 | 0x8a3e52c | real |
| 27 | 0x8a3e93c | ldr x0,[x0]; ldr x8,[x0]… forwarding-ish |
| 28 | 0x8a3e964 | real |
| 31 | 0xf42d74c | pure virtual (`ret; brk #1`) |
| 34 | 0x8a43878 | real |
| 37 | 0xf42d74c | pure virtual |
| 40 | 0x8a43d7c | real |
| 45–149 | 0x88d8d70..0x88d9478 | ~100 constant-return stubs (`mov w0,#1; ret`) |
| 123 | 0x8a45468 | big fn (sub sp,#0x290) |
| 143 | 0x8a60f9c | tiny stub |
| 144 | 0x8a61080 | real |

Truth notes: high estimate of constant stubs ⇒ 9CameraAPI is largely interface-default
overrides (scripting-facing). The real work lives in slots 2–17 + 22–28 + 34/40/123.
0xb7c8b9c (FOV ease) and 0x8a42760 (entt lookup) are DIRECT call callees (non-virtual),
reached from slots 6/7 (and elsewhere), confirmed by reading both.

### CameraAPI slot decode, full bytes (2026-11-12, 256 B each, Codespaces)
- **slots 2/3/5/16/17 = identical twin family** ("camera target ease variants"):
  this+8 inner object → `bl #0x8897370` (w0=1, register-ish) → virtual call
  `[vtable+0x538]` → `bl #0xe82f8c0` → `bl #0xe82f8c4` (tbz w0 bit0) →
  `bl #0xfdb3b58` → `bl #0xe80c714` → if x21 null skip.
  Result: `fmov s8..s10 = s0..s2 (input vec3)`; `bl #0xfd86998` (or `#0x9c7568c` in
  slot3); load mem vec3 at x0+0/4/8; `fsub` → delta; pack
  **x0 = (delta_z_bits<<32)|delta_x_bits, x1 = (1<<32)|delta_y_bits** (slot2),
  or eased-scalar pack (slot3: `bl #0x9c7568c`, `orr x1, x8, #0x100000000`).
  → These are the /camera ease (`InOutCubic` etc.) TARGET-RESOLVE/DELTA functions.
- **slot 4**: entt lookup, hash 0x75DF36B7 (`mov w10,#0x36b7` + `movk w10,#0x75df,lsl#16`),
  followed by the same `movk w13,#0x31a8,lsl#16`/0x31A824ED compare seen in headGet.
- **slots 9/11** = near-identical twin pair.
- **slots 10/12/14/16** = SAME body 4 copies (byte-identical up to tiny tails).
- **slot 13**: pure tail-call forwarder (`ldr x0,[x0]; ldr x8,[x0]; ldr x1,[x8,#0x100]; br x1`).
- **slot 15**: vec3-store variant (`bl #0x9c7568c`-style, stores via `sp+0x18`/st0 pattern).

**Verdict**: CameraAPI = vanilla `/camera` command API + ease machinery
(template-instantiated twin copies). It is NOT the per-frame camera pose writer.
The per-frame pose path lives in LevelRendererCamera / the entt CameraComponent
(hash 0x18b1887 family). Next search: find code refs to 0x18b1887.

Encodings used for the search (verified against disassembled samples):
  movk wN,#0x2c7,lsl#16  == 0x72A058EC+N pattern (bytes `ec 58 a0 72` for w12)
  movk wN,#0x31a8,lsl#16 == 0x72A63500+N
  movk wN,#0x18b,lsl#16  == 0x72A03160+N   (0x18B1887 hi part)
  mov  wN,#0x1887        == 0x528310E0+N   (0x18B1887 lo part)

---

## Phase 3 (2026-11-12) — fnX/fnY decode + headSet sig hit

/tmp is NOT persistent — all raw dumps were lost; `/home/user/.venv-re` (capstone 5.0.7)
recreated in-home. fnX tail disasm preserved at `docs/re-artifacts/fnX_tail_b949f00.disasm.txt`.

### fnY @ 0x8a79c00 = ELIMINATED
Body = generic vector-insert / memcpy family (`stp q0,q1` loop, `cmp x1,#0`, iterator math).
No camera/entt content. Not a candidate.

### fnX @ 0xb949c0c — find-or-CREATE twin of headGet (WRITES a pose double)
Tail of prior fn occupies 0xb949c00..0xb949c08; fnX entry = 0xb949c0c.
- stack-protect prologue (`tpidr_el0`), caller-saved hash `w9=0x62a6b9c`
  (`mov #0xb9c` + `movk #0x62a6,lsl#16`), component stride 0x130
  (`umaddl x23,w9,w10(0x130),x8`), gate byte `ldrb [x23+0x11d]`
- gate chain `0xaf5d23c -> 0x9c706c0 -> 0x1346923c` (`tbz w0,#0` -> shared skip @0xb949f5c)
- second pool hash `0xfd83aa62`; interface fetch `ldr x8,[x0]; ldr x8,[x8,#0x100]; blr x8`
- predicate `0x10be277c`
- then the two headGet double-hash reads: `mov w1,#0x4480; movk w1,#0x2c7,lsl#16`
  (0x02C74480) -> `bl #0x9f343c8` @0xb949e10; `mov w1,#0x24ed; movk w1,#0x31a8,lsl#16`
  (0x31A824ED) -> `bl #0x8a796b8` @0xb949ebc
- KEY STORE (tail): `ldr d0,[x9,#0x120]` (x9 = saved 0x62A6B9C-family component +0x120);
  `str d0,[x8]` where x8 = 0x31A824ED pool slot `pool[(id&0x7f)*8]` for entity x21;
  `add x1,x9,#0x120`; `bl #0xfd8f694` (double\* setter). Shared exits 0xb949f10 / 0xb949f5c.
- NOT identical to BedrockTools `headSet` AOB (sig scan hits elsewhere — see below).
  fnX = an internal find-or-create sibling of headGet on the SAME data path
  (headGet READS the 0x31A824ED double; fnX WRITES it).

### headSet (0x8d) full-.so scan result
`headSet(0x8d) hits: ['0xfdafbbc']` — the ONLY full-file match for the 413-byte AOB.
=> **fnX is NOT headSet.** The authoritative write-path function is at **0xfdafbbc**
   (decoded shape: inlined entt pool lookup, 32-byte pool stride, ends in `str` — consistent
   with a head/pose struct writer). Untested against disasm yet.

### Census (movk-lsl#16 scan, full .so)
- `0x84240000` → 9 hits (8a3db18 in slot6 body; else scattered) — unusable
- `0x31a824ed` → 4: 0x8a42844, 0x8a428d4 (headGet), 0x8a79e44 (fnY), 0xb949eb8 (fnX)
- `0x2c74480` → 18 hits (incl headGet 0x8a4278c and fnX 0xb949e0c)
- others too common: 0x75df36b7 (146), 0x3727c5ac (392), 0x3eb91730 (44), 0x18b1887 (103)
- FNV-1a/1 name hashing → no matches; MC entt hash is custom — do not retry name hashing.

### Verdict so far
Write-path candidates, in order of authority:
1. **headSet @ 0xfdafbbc** — upstream signature match (authoritative). Needs disasm.
2. fnX @ 0xb949c0c — internal writer twin of headGet (find-or-create). Secondary.
hook target for pov_mod v1.3 NOT confirmed yet. NO builds.

### Next user step
Dump headSet region from Codespaces:
    python3 - <<'EOF'
    data = open('libminecraftpe.so','rb').read()
    print(data[0xfdafb00:0xfdafd80].hex(' '))
    EOF
(640 B window, 0xfdafb00..0xfdafd80 — headSet entry is at/near 0xfdafbbc.)

## Phase 4 (2026-11-12) — headSet sig region disassembled (0xfdafb00..0xfdafd80)

Two near-identical leaf functions (no frame, no stack-protector — direct entt double-hash walk,
hash **0xBABE7211**, pool stride 0x20, `ubfx x11,x8,#0xb,#7` / `and x9,x9,#0x7f` slot math):

- **fnA (SETTER, entry ~0xfdafafc — clipped by dump start)**: ends `stp s0,s1,[x8]` @0xfdafbb4,
  `ret` @0xfdafbb8. Writes two floats (s0,s1) into the 0xBABE7211 pool slot for entity in x0.
  => THE real head pose (yaw/pitch) writer on this build.
- **fnB @ 0xfdafbbc (GETTER)**: `ldr x9,[x0,#0x10]` … `ldp s0,s1,[x8]` @0xfdafc7c; zero-init
  `movi d0,#0` / `movi d1,#0` failure paths (returns empty). => what the BedrockTools
  **headSet (0x8d) AOB actually matches** — the sig's immutable `00 e4 00 2f` (movi d0,#0)
  can only anchor in this getter twin. On this build the AOB is semantically WRONG for a writer.
- **fnC @ 0xfdafc8c (prologue, callee-saved)**: hash **0x68b75d48**, stride 0x20, same double-hash
  shape; tail beyond 0xfdafd80 not yet seen — decode pending.

Correction to Phase 3 verdict: "headSet @ 0xfdafbbc = authoritative write path" was WRONG.
0xfdafbbc is the getter. fnA (~0xfdafafc) is the setter; fnX @ 0xb949c0c stores a double into the
0x31A824ED pool (different component family). hook target for v1.3 still NOT confirmed.

### Next user step
One command, two slices:
    python3 - <<'EOF'
    data = open('libminecraftpe.so','rb').read()
    print('A:', data[0xfdafac0:0xfdafb00].hex(' '))
    print('C:', data[0xfdafd80:0xfdafdc0].hex(' '))
    EOF

## Phase 5 (2026-11-12) — entry/exit confirmations

- **fnA entry CONFIRMED @ 0xfdafafc** (`ldr x9,[x0,#0x10]`); body continues through
  0xfdafb00..0xfdafbb8; ends `stp s0,s1,[x8]`. fnA = head float2 SETTER (hash 0xBABE7211).
- 0xfdafaf0/4: sibling scalar-path epilogue — `lsl x9,x9,#3; str s0,[x8,x9]` (single-float
  store, hash 0xBABE7211 family, 4-byte payload slot).
- fnC (0xfdafc8c, hash **0x68b75d48**) jump targets all converge on **0xfdafde4**
  (its common exit — presumably the store/return block). Tail NOT yet dumped.
- Slice artifacts: docs/re-artifacts/sliceA.bin, sliceC.bin.

### Next user step
    python3 - <<'EOF'
    data = open('libminecraftpe.so','rb').read()
    print(data[0xfdafdc0:0xfdafdf4].hex(' '))
    EOF

## Phase 6 (2026-11-12) — fnC tail decoded; family closed

sliceD (0xfdafdc0..0xfdafdf4):
- 0xfdafdc0 cmp w10,w11 / b.hi -> 0xfdafde4 (`mov x21,xzr`, null result)
- 0xfdafdc8 and x10,x9,#0x3ff80 ; lsr #4 ; ldr x8,[x8,x10]
- and x9,x9,#0x7f ; **add x21,x8,x9,lsl#2**  -> computes int32-slot pointer, stores into x21
- 0xfdafde8: cbz x0,->; ldr x8,[x0]; **ldr x8,[x8,#0x468]** -> vtable slot 0x468 dispatch follows
=> fnC = component-POINTER resolver (int32 index math) feeding a virtual call; NOT a pose store.
   fnC eliminated as hook candidate. The pose-writer family is:
   fnA0 (scalar s0 store) · fnA @0xfdafafc (float2 s0/s1 store, 0xBABE7211) · fnB @0xfdafbbc (getter).

## Phase 7 (2026-11-12) — pov_mod.log (v1.2.0 device session, 2026-11-10 18:46) ANALYSED

This was in the uploads all along; analysed now. THE definitive v1.2 failure evidence:

- hooks install: UpdatePlayerFromCamera=ok LocalPlayerApplyTurnDelta=ok RenderLevel=ok (18:46:42)
- renderLevelDetour FIRST call: renderer=0xb400007afcad0e90 context=... view=0xb400007a3d7259d0 (18:47:07)
- override frames: **writes=0 restores=0 lifetime; injectFail=N increments every frame (→5858+)**
- **probe=2 (ProbeState::FAILED), stride=0x0, viewOffsetZero=0, slot=0x0 — EVERY sample (31/31)**
- => v1.2's failure is the ECS walk: hash **0x4f6047c7** (Do-A-Barrel-Roll's Windows-side
  CameraComponent hash) never resolves on this Android build (custom entt hash; DABR hash is
  Windows-name-derived and matches nothing). updatePlayer skip + turn-delta drop DID work (dropped=N grows),
  but pose was never injected. v1.2 failed 100% because of the ECS path, NOT the hook points.
- **BUT direct memory access WORKS**: `renderer + 0x430 (mLevelRendererPlayer) -> LRP`,
  `LRP + 0x614 -> float3 live camera pos` — logged every ~2s (18:47:07 → 18:48:43), values track
  the world camera correctly (e.g. 173.569,64.620,18.033 → 171.156,64.620,17.902 → ... jumps too).
- => the hook is VALID; the pose override should write DIRECTLY into the LevelRendererPlayer
  struct (pos at +0x614 confirmed) instead of the (nonexistent) CameraComponent pool.
- Orientation offset unknown: candidates near +0x5fc..0x620 (yaw/pitch floats, forward vec3, or quat
  before/after mCamPos). Plan: extend the probe to hex-dump LRP[0x580..0x700) every 2s AND auto-search
  the window for the live RotationComponent pitch/yaw float bits (mod already reads these for
  "Snap to game view") — unit-agnostic (raw float-bit compare). One device run pins the offset.
- v1.3 design (pending user go-ahead): drop entt ENTIRELY; in renderLevelDetour BEFORE orig:
  write override pos (+0x614) and orientation (discovered offsets) into LRP; AFTER orig: byte-restore
  (same snapshot pattern as v1.2). Keep UpdatePlayerFromCamera skip + turn-delta drop (vanilla gameplay).
- if raw-float search finds nothing (camera stores cos/sin or basis instead of yaw/pitch), fallback:
  dump + match against menu-set known yaw (0/90/180/-90) at screenshot time.

Logs: /tmp/arena-coding-uploads/pov_mod.log (device log, 25 KB, 3 sessions v1.0/1.1/1.2).
## Phase 8 (2026-11-13) — zaphkiel dump analysis (SHIPPED AS v1.4 — FAILED ON DEVICE; see v1.4 postmortem)

> **DISCLAIMER (added after the device failure): the conclusion "fn_0xb7d4274 is THE
> camera-pose writer of LevelRendererPlayer" was NEVER verified.** The dumps show a float3
> stored at `this+0x614`, but object identity was assumed, never confirmed (no ctor/vtable
> cross-ref). On device the writes corrupted movement/attack controls — `this` is not (only)
> the LRP. Kept below for the record; do NOT build from this phase again as-is.

User ethstruct'd the 0xb7a-b7d render module (+0x614 consumers/writers) and uploaded
`1.zip` (302 FC targets, 149 .c dumps, zaphkiel_out.txt) to repo root on main; v1.3.0
(direct LRP+0x614 write around RenderLevel) does NOT move the view on device.

### fn_0xb7d4274 — the `this+0x614` ease/tick (v1.4 hook target — REFUTED on device)
- tpidr_el0 stack-protect prologue; args (x0=this=r19, x1=r21 flag-holder, byte @r21+0x4e);
  global enable gate at 0x1407f080; global state at [0x1407f080..]+0x160 (r22) + byte flags — skips if disabled.
- Reads desired/blend target from r19+0x5d8/0x5dc/0x5e0 (float3) and cached target r19+0x5e4/0x5ec;
  fn_0x11f0ca80 formats float3 (debug/log paths — used in >15 dumps).
- Distance check vs eased threshold (4x / 8..16 px) -> fn_0xb7a9370 (ease) or EVICT to
  '"moveRenderingArea task"' (0x24a7788) async task (>threshold jumps).
- Cached target update at r19+0x5e4/0x5ec (+fn_0xb7a9068 notify if changed).
- END STORE (label_0xb7d46d0): r19+0x614 <- float3 (sp+0x5c) — the value the renderer reads;
  + 0x61c/0x620/0x628 (d7c/d84 region) — more pose/FOV state (NOT position; leave untouched).
- => (v1.3 wrote BEFORE RenderLevel; the view matrix is built at/before this fn — v1.3 was too late.) NOTE: this whole chain of reasoning was shaken by the v1.4 failure; frame order unknown.

### Other +0x614 sites (secondary; NOT to hook)
- fn_0xb7c7268: struct-copy fn — copies r19+0x8/0x10/0x14 into r20+0x614/0x61c/0x620 (set-pose API / jump cut).
- fn_0xb7b1c18 / fn_0xb7a10e0 / fn_0xb7a7d08 / fn_0xb7afc3c / fn_0xb7d3d4c / fn_0xb7bf714: calls
  fn_0x11f0ca80(this, r1=X+0x614) — float3-format helper (debug logs), reads only.
- fn_0xb7c3b60: reads +0x614/0x618/0x61c after fn_0xfd86998 (component get-double*!) + fsub.
- fn_0x27da444 (4.6 MB): .rodata STRING TABLE (ASCII log strings; misdumped as code).
- fn_0x135d3690 (3.1 MB): giant logging/trace fn zoo ("BgfxFrameBuilder", "FrameBuilder - Insert").
- 0x9a5xxxx/0x9c1xxxx clusters: particle/emitter + UI ("cameraOverlay" particle layer) — unrelated.

### v1.4 design (hook = fn_0xb7d4274 wrapper)
1. x0 = this (LevelRendererPlayer-ish). call orig; if override ON: cur = float3 LRP+0x614 (post-orig,
   = vanilla eased target); target = relative ? cur + offset : absolute; write back float3 at +0x614.
2. IMPLEMENTED AS-BUILT: no snapshot/restore at all (vanilla rewrites +0x614 every tick, OFF is clean).
   RenderLevel detour demoted to diagnostic-only (live LRP+0x614 log). UpdatePlayerFromCamera skip
   + TurnDelta passthrough unchanged. CamSetter fn_0xb7c7268 hooked with same post-orig overwrite
   (packet path coverage).
3. Signatures: user generated BOTH on-device — CamTick = fn_0xb7d4274 (14 insns, UNIQUE, quality 515),
   CamSetter = fn_0xb7c7268 (14 insns, UNIQUE, quality 543). Both wired as AOB (pl::memory).
4. zaphkiel abs-dump addresses == .so vaddr (identity map seg0 confirmed earlier); AOB sigs used
   instead of raw va for portability. Local host tests PASS (composeCamPos added to PovCore).

---

## v1.4 postmortem (2026-11-13) — build SHIPPED AND FAILED ON DEVICE

v1.4 wrapped fn_0xb7d4274 ("CamTick" sig: `FF C3 06 D1 EA A3 00 FD ...` matches=1 q515)
and fn_0xb7c7268 ("CamSetter" sig: `FD 7B BE A9 F4 4F 01 A9 ...` matches=1 q543), and
after the original ran overwrote the 12-byte float3 at `this+0x614` (relative: live+offset,
absolute world XYZ), keeping the UpdatePlayerFromCamera skip and TurnDelta pass-through.

**On-device result (user report):** the camera never moved; the movement/attack controls
were corrupted — joystick mirrored ("aage jata hai to peeche, left pe right, aage pe
right bhi") and the attack button behaves reversed. Install = uninstalled the game feel.

**Root-cause verdict:** the object `this` in fn_0xb7d4274 / fn_0xb7c7268 is NOT (only) the
renderer's LevelRendererPlayer. The shared `+0x614` float3 was assumed to be the camera
pose in BOTH structs without verifying object identity (no ctor/vtable cross-ref was ever
checked for these two functions). The writes steamrolled an input/movement-consumed
float3. Secondary risk: both detours were typed `void(*)(void*,void*)` — if the real
functions return non-void, the wrappers leave garbage in `x0` for callers.

**Process failure that made it bad:** the Zaphkiel dump analysis (148 fns) lived only at
`/home/user/re_user/` OUTSIDE the repository; a sandbox reset deleted the dumps AND
CAMERA-RE-NOTES.md before any of it was verified on device. Lesson: all RE artifacts and
notes belong INSIDE the repo (`docs/`, `docs/re-artifacts/`) — hence everything here.

**Resolution (v1.5.0 SAFE, same day):** removed ALL writes and ALL behavioral hooks.
Only a read-only RenderLevel diagnostic remains (pass-through, logs `renderer+0x430 ->
LRP+0x614` every ~2 s). Menu stripped to X/Y/Z + Relative + Reset (saved, inert).
Game is 100% vanilla again. NO MORE BUILDS OF THE OVERRIDE until the pose consumer is
identified and verified on device, in this order:
  1. Re-dump fn_0xb7d4274 / fn_0xb7c7268 AND their callers (who passes `this` = the
     camera object?). The caller set defines the class.
  2. Confirm frame order: who READS LRP+0x614 and when, relative to RenderLevel and to
     the writer (both write points failed: v1.3 pre-orig RenderLevel = static view;
     v1.4 post-CamTick = broken controls).
  3. Only then ship a write build, and gate it behind a log line that prints the live
     LRP float3 each second so the first device run is self-verifying.
Also still open: whether v1.3's "static screen" was frame-order (writer runs after
RenderLevel) or the same wrong-object bug — the v1.4 chaos makes v1.3's result
inconclusive. Evidence to request from the user when possible: kurumi/pov_mod.log from
the v1.4 session (contains "override: FIRST LRP+0x614 write cur=... target=..." and the
periodic "renderer camera pos" lines).

---

## Phase 9 (2026-11-13) — ACTUAL freelook SOURCE reviewed (user-pasted freelook.cpp)

User pasted `freelook.cpp` (BedrockTools freelook module source). This supersedes the
binary-only reading of Phase 1 AND explains why pov v1.2-v1.4 failed: we never had this.

### What the source proves (vs my earlier claims)

* Hooks resolved: `UpdatePlayerFromCamera`, `LocalPlayerApplyTurnDelta`,
  `GetPerspective` (***not*** RenderLevel — RenderLevel was never a freelook hook; my v1.2/v1.3
  relied on it as if it mattered), `ActorGetHeadRotation`, `ActorSetHeadRotation`.
* `updatePlayerDetour(camera, component, player)`: if player != localPlayer() → passthrough.
  If freelook active → **restore saved player state + input component, do NOT call original**
  (body never syncs from camera). Else → passthrough to original. MotionBlurCaptureCamera()
  called on BOTH paths after the decision.
* `restorePlayer` memcpys **float[4]** (16 bytes!) via `player->rotationComponent()` —
  NOT float[2]. Our pov_mod read 8 bytes here — would misread orientation always.
* Camera pose lives in the **CameraComponent** object passed as `camera` to the detour;
  freelook saves/restores `camera + sdk::offsets::CameraComponent::mOrientation` (16 B).
  (My Phase 1 "camera+0x238" guess was the wrong struct/offset — entangled with rotationComponent.)
* Head rotation is a plain **sdk::Vec2** on Player (getHeadRotation/setHeadRotation).
  The 0x8d signature is the Vec2 writer, not some complex pose writer; my Phase 4-6 "entt pool
  writer with 0xBABE7211" decode was over-analysis of a simple fn (lookup pool included but it's
  just the component fetch).
* **InputComponent (entt hash 0x018b1887 — same hash, confirmed)**: context → registry
  (field@0x8) → buckets@0x38/0x40 → nodes@0x50/0x58 (stride 0x20) → pool (node+0x10) →
  entity = field(context,0x10) → **sparse set** (pool+0x8/0x10, page = (entity & 0x3ffff)>>11) →
  **packed entity** u32 per page slot; `packed ^ (entity & 0xfffc0000) > 0x3fffe` → null;
  payload (pool+0x50/0x58, page=(packed & 0x3ffff)>>7) → **InputComponent* = storage + (packed & 0x7f) * 0x64**.
* **InputComponent layout**: `cameraOrientation` (sdk::Vec3) at **+0x54**; `flags` (u16) at **+0x60**;
  whole component stride **0x64**. `movementFlags = (1<<9) | (1<<10)` — the two bits freelook
  saves/restores so movement direction stays player-relative while the camera orbits.
* `samePlayer()` requires same player pointer AND same `level()` AND same `dimension()` —
  identity across respawn/world change; we had no such guard.
* `requested() = module enabled && hooksReady && !keybindBlocked()`.
* On disable/unload everything is restored from the session (rotation, headRotation,
  input cameraOrientation, flags bits 9|10) — full rollback discipline.
  POV mod had no equivalent restore → leaked state.

### Lesson applied to pov_mod

The ONLY proven way to move the camera on this build: hook `UpdatePlayerFromCamera` exactly
like this — keep `camera` (CameraComponent*) and `player`, call original (or skip), then
override the camera pose fields — with session save/restore and localPlayer/level/dimension
identity checks. Position override = same pattern, one field; need
`sdk::offsets::CameraComponent` (mPosition?) from the SDK headers (client-side) to pick the
right offset — none of the addresses we guessed (LRP+0x614 etc.) were needed/verified.

### Still missing to implement POV correctly

1. The BedrockTools SDK headers (this source's includes): `sdk/offsets.hpp` (CameraComponent
   position/orientation offsets, ClientInstanceGetLocalPlayer sig id), `sdk/client/ClientInstance.hpp`,
   `modules/ModuleRegistry.hpp`, `launcher/OverlayBridge.hpp`, `motionblur.hpp`, `core/*`.
   -> Ask the user for the repo/source path these reference, or the repo URL.
2. A device confirmation whether "MotionBlurCaptureCamera" is required for the view to pick up
   the overridden pose (freelook always calls it).
