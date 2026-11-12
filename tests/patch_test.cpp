// Host test: g++ -std=c++20 -I../src patch_test.cpp ../src/WaterPatch.cpp -o patch_test && ./patch_test
#include <cassert>
#include <cstdio>
#include <cstring>
#include <vector>

#include "WaterPatch.hpp"

using namespace wp;

struct MemWriter : Writer {
  int writes = 0;
  bool fail = false;
  bool write32(uint8_t* a, uint32_t w) override {
    if (fail) return false;
    std::memcpy(a, &w, 4);
    ++writes;
    return true;
  }
};

static uint32_t rd(const std::vector<uint8_t>& b, uint64_t off) {
  uint32_t w;
  std::memcpy(&w, b.data() + off, 4);
  return w;
}
static void put(std::vector<uint8_t>& b, uint64_t off, uint32_t w) { std::memcpy(b.data() + off, &w, 4); }

int main() {
  // 1) encodings must reproduce the exact words read from the real disassembly
  assert(encodeMovzW(26, 5) == 0x528000BAu);   // BA 00 80 52
  assert(encodeMovzW(26, 1) == 0x5280003Au);   // 3A 00 80 52
  assert(encodeMovzW(8, 30) == 0x528003C8u);   // C8 03 80 52
  assert(encodeMovzW(9, 10) == 0x52800149u);   // 49 01 80 52
  assert(encodeMovzW(1, 5) == 0x528000A1u);    // A1 00 80 52 (guard)
  assert(encodeCmpWImm(8, 2) == 0x7100091Fu);  // 1F 09 00 71
  assert(encodeCmpWImm(8, 1) == 0x7100051Fu);  // 1F 05 00 71

  // 2) default table is self-consistent: vanilla word == encoding of the vanilla value
  Config vanilla;
  for (const auto& p : defaultTable().patches) assert(encode(p, vanilla.*(p.value)) == p.original);
  for (const auto& g : defaultTable().guards) assert(g.expected == encodeMovzW(1, 5));

  // 3) config parsing
  {
    Config c;
    std::vector<std::string> warn;
    bool ok = parseConfig("# c\nwater_delay = 1\nlava_delay_overworld=7 # x\nbogus=3\nsource=abc\nnoequals\n", c, warn);
    assert(ok && c.waterDelay == 1 && c.lavaDelayOverworld == 7 && c.lavaDelayNether == 10 && c.sourceCount == 2 && !c.dryRun);
    assert(warn.size() == 3);
    Config dr;
    std::vector<std::string> w4;
    assert(parseConfig("dry_run=1\n", dr, w4) && dr.dryRun && w4.empty());
    Config d;
    std::vector<std::string> w2;
    assert(parseConfig(defaultConfigText(), d, w2) && w2.empty() && d.waterDelay == 5 && d.sourceCount == 2);
    Config e;
    std::vector<std::string> w3;
    assert(!parseConfig("", e, w3));
  }

  // 4) apply / verify / restore on a fake library using small offsets
  Table t;
  t.patches = {
      {"w_a", 0x100, 0x528000BAu, Kind::MovzW, 26, &Config::waterDelay, 1, 2000},
      {"w_b", 0x200, 0x528000BAu, Kind::MovzW, 26, &Config::waterDelay, 1, 2000},
      {"lava", 0x300, 0x528003C8u, Kind::MovzW, 8, &Config::lavaDelayOverworld, 1, 2000},
      {"src", 0x400, 0x7100091Fu, Kind::CmpWImm, 8, &Config::sourceCount, 1, 8},
  };
  t.guards = {{0xFC, 0x528000A1u}};
  std::vector<uint8_t> mem(0x1000, 0);
  put(mem, 0xFC, 0x528000A1u);
  put(mem, 0x100, 0x528000BAu);
  put(mem, 0x200, 0x528000BAu);
  put(mem, 0x300, 0x528003C8u);
  put(mem, 0x400, 0x7100091Fu);

  assert(verify(mem.data(), mem.size(), t).empty());
  assert(!verify(mem.data(), 0x300, t).empty());  // site outside mapped size

  MemWriter w;
  Config c;
  c.waterDelay = 1;
  c.lavaDelayOverworld = 4000;  // clamped to 2000
  c.sourceCount = 1;
  std::vector<std::string> log;
  assert(apply(mem.data(), c, t, w, log) == 4);
  assert(rd(mem, 0x100) == encodeMovzW(26, 1) && rd(mem, 0x200) == encodeMovzW(26, 1));
  assert(rd(mem, 0x300) == encodeMovzW(8, 2000));
  assert(rd(mem, 0x400) == encodeCmpWImm(8, 1));
  assert(log.size() == 4);
  assert(verify(mem.data(), mem.size(), t).empty());  // our own patch is accepted (toggle off/on)

  assert(restore(mem.data(), t, w) == 4);
  assert(rd(mem, 0x100) == 0x528000BAu && rd(mem, 0x400) == 0x7100091Fu);

  // 5) wrong build -> refuse
  put(mem, 0xFC, 0xD503201Fu);  // nop instead of mov w1,#5
  assert(verify(mem.data(), mem.size(), t).find("different Minecraft version") != std::string::npos);
  put(mem, 0xFC, 0x528000A1u);
  put(mem, 0x300, 0xD65F03C0u);  // ret
  assert(verify(mem.data(), mem.size(), t).find("unexpected instruction") != std::string::npos);

  // 5b) unreadable memory is reported, never dereferenced
  put(mem, 0x300, 0x528003C8u);
  assert(!verify([](uint64_t, uint32_t&) { return false; }, t).empty());
  assert(verify([&](uint64_t off, uint32_t& w) { if (off > 0x300) return false; w = rd(mem, off); return true; }, t).find("not readable") != std::string::npos);

  // 6) failing writer is reported, not hidden
  put(mem, 0x300, 0x528003C8u);
  MemWriter bad;
  bad.fail = true;
  std::vector<std::string> log2;
  assert(apply(mem.data(), c, t, bad, log2) == 0 && log2[0].find("FAILED") != std::string::npos);

  // 7) Infinite Flow table: vanilla is cap 7 everywhere; Fixed site swaps csel->mov
  {
    const Table ft_vanilla = flowTable(false);
    assert(ft_vanilla.patches.size() == 5);
    for (const auto& p : ft_vanilla.patches) assert(encode(p, 7) == p.original);
    const Table ft = flowTable(true);
    assert(ft.patches.size() == 6);
    const auto& fixed = ft.patches.back();
    assert(fixed.kind == Kind::Fixed && fixed.original == 0x1A88C018u && encode(fixed, 15) == 0x2A0003F8u &&
           encode(fixed, 1) == 0x2A0003F8u);  // value ignored
    assert(!ft.guards.empty());
    // verify accepts its own patch applied; rejects a foreign instruction at the Fixed site
    Table small;
    small.patches = {
        {"fixed", 0x100, 0x1A88C018u, Kind::Fixed, 0, &Config::spreadLimit, 1, 15, 0x2A0003F8u},
        {"cap", 0x200, 0x71001D5Fu, Kind::CmpWImm, 10, &Config::spreadLimit, 1, 15, 0},
    };
    small.guards = {};
    std::vector<uint8_t> mem2(0x400, 0);
    put(mem2, 0x100, 0x1A88C018u);
    put(mem2, 0x200, 0x71001D5Fu);
    assert(verify(mem2.data(), mem2.size(), small).empty());
    put(mem2, 0x100, 0x2A0003F8u);  // our word => accepted
    assert(verify(mem2.data(), mem2.size(), small).empty());
    put(mem2, 0x100, 0xD503201Fu);  // foreign word => refused
    assert(!verify(mem2.data(), mem2.size(), small).empty());
    // cap encoding at 15
    assert(encodeCmpWImm(10, 15) == 0x71003D5Fu);
    assert(encodeCmpWImm(25, 15) == 0x71003F3Fu);
    assert(encodeCmpWImm(24, 15) == 0x71003F1Fu);
    assert(encodeCmpWImm(22, 15) == 0x71003EDFu);
    // flow.cfg roundtrip
    Config fc;
    std::vector<std::string> fw;
    assert(parseConfig("spread_limit=9\nkeep_flow_type=0\ndry_run=1\n", fc, fw));
    assert(fc.spreadLimit == 9 && !fc.spreadKeepType && fc.dryRun && fw.empty());
    Config fd;
    std::vector<std::string> fdw;
    assert(parseConfig(flowConfigText(), fd, fdw) && fd.spreadLimit == 15 && fd.spreadKeepType && fdw.empty());
  }

  std::puts("ALL PATCH TESTS PASSED");
  return 0;
}
