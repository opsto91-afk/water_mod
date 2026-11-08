#include "WaterPatch.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>

namespace wp {

namespace {

std::string trim(const std::string& s) {
  size_t a = 0, b = s.size();
  while (a < b && std::isspace((unsigned char)s[a])) ++a;
  while (b > a && std::isspace((unsigned char)s[b - 1])) --b;
  return s.substr(a, b - a);
}

uint32_t readWord(const uint8_t* p) {
  uint32_t w;
  std::memcpy(&w, p, 4);  // little endian on arm64 / x86
  return w;
}

std::string hex(uint64_t v) {
  char b[32];
  std::snprintf(b, sizeof b, "0x%llx", (unsigned long long)v);
  return b;
}

}  // namespace

std::string defaultConfigText() {
  return "# Water Tweaks - edit, then switch the module OFF and ON in the Mod Menu.\n"
         "# Delays are in game ticks (20 ticks = 1 second).\n"
         "water_delay=5\n"
         "lava_delay_overworld=30\n"
         "lava_delay_nether=10\n"
         "infinite_source_count=2\n"
         "dry_run=0   # 1 = only check + log, never write memory\n";
}

bool parseConfig(const std::string& text, Config& out, std::vector<std::string>& warnings) {
  std::istringstream in(text);
  std::string line;
  int parsed = 0;
  while (std::getline(in, line)) {
    auto hash = line.find('#');
    if (hash != std::string::npos) line.erase(hash);
    line = trim(line);
    if (line.empty()) continue;
    auto eq = line.find('=');
    if (eq == std::string::npos) {
      warnings.push_back("ignored line (no '='): " + line);
      continue;
    }
    std::string key = trim(line.substr(0, eq)), val = trim(line.substr(eq + 1));
    char* end = nullptr;
    long v = std::strtol(val.c_str(), &end, 10);
    if (val.empty() || *end != '\0' || v < 0) {
      warnings.push_back("bad value for " + key + ": '" + val + "'");
      continue;
    }
    if (key == "water_delay") out.waterDelay = (uint32_t)v;
    else if (key == "lava_delay_overworld") out.lavaDelayOverworld = (uint32_t)v;
    else if (key == "lava_delay_nether") out.lavaDelayNether = (uint32_t)v;
    else if (key == "infinite_source_count") out.sourceCount = (uint32_t)v;
    else if (key == "dry_run") out.dryRun = (v != 0);
    else {
      warnings.push_back("unknown key: " + key);
      continue;
    }
    ++parsed;
  }
  return parsed > 0;
}

uint32_t encodeMovzW(uint32_t rd, uint32_t imm16) { return 0x52800000u | ((imm16 & 0xFFFFu) << 5) | (rd & 0x1Fu); }
uint32_t encodeCmpWImm(uint32_t rn, uint32_t imm12) {
  return 0x7100001Fu | ((imm12 & 0xFFFu) << 10) | ((rn & 0x1Fu) << 5);
}
uint32_t encode(const Patch& p, uint32_t value) {
  return p.kind == Kind::MovzW ? encodeMovzW(p.reg, value) : encodeCmpWImm(p.reg, value);
}

// ---------------------------------------------------------------------------------------------
// Real sites, found with Zaphkiel in libminecraftpe.so (main liquid tick worker at 0x1088d268).
// Original words are the exact encodings of the disassembly that was read:
//   0x1088d3ec / 0x1088d7bc   mov w26, #5      water tick delay   (right after "mov w1,#5 ; bl isType")
//   0x1088d7ec                mov w8,  #0x1e   lava delay, overworld  \  followed by
//   0x1088d7f4                mov w9,  #0xa    lava delay, nether     /  csel w26, w9, w8, ne
//   0x1088d990                cmp w8,  #2      adjacent sources needed for a new source (water only)
// Guards: "mov w1,#5" in front of both water sites (0x1088d3e8 / 0x1088d7b8).
const Table& defaultTable() {
  static const Table t = {
      {
          {"water_delay (a)", 0x1088d3ec, 0x528000BAu, Kind::MovzW, 26, &Config::waterDelay, 1, 2000},
          {"water_delay (b)", 0x1088d7bc, 0x528000BAu, Kind::MovzW, 26, &Config::waterDelay, 1, 2000},
          {"lava_delay_overworld", 0x1088d7ec, 0x528003C8u, Kind::MovzW, 8, &Config::lavaDelayOverworld, 1, 2000},
          {"lava_delay_nether", 0x1088d7f4, 0x52800149u, Kind::MovzW, 9, &Config::lavaDelayNether, 1, 2000},
          {"infinite_source_count", 0x1088d990, 0x7100091Fu, Kind::CmpWImm, 8, &Config::sourceCount, 1, 8},
      },
      {
          {0x1088d3e8, 0x528000A1u},  // mov w1, #5
          {0x1088d7b8, 0x528000A1u},  // mov w1, #5
      }};
  return t;
}

std::string verify(const ReadWord& read, const Table& t) {
  auto check = [&](uint64_t off, uint32_t& w) -> std::string {
    if (!read(off, w)) return "offset " + hex(off) + " is not readable (library not fully mapped?)";
    return "";
  };
  for (const auto& g : t.guards) {
    uint32_t w = 0;
    if (auto e = check(g.offset, w); !e.empty()) return e;
    if (w != g.expected) return "guard mismatch at " + hex(g.offset) + " (found " + hex(w) + ") - different Minecraft version";
  }
  for (const auto& p : t.patches) {
    uint32_t w = 0;
    if (auto e = check(p.offset, w); !e.empty()) return e;
    bool vanilla = (w == p.original);
    // already patched by us earlier (module toggled off/on) is fine: same instruction kind and register
    uint32_t mask = p.kind == Kind::MovzW ? 0xFF80001Fu : 0xFFC003FFu;
    bool ours = ((w & mask) == (p.original & mask));
    if (!vanilla && !ours) return std::string("unexpected instruction at ") + hex(p.offset) + " for " + p.name + " (found " + hex(w) + ")";
  }
  return "";
}

std::string verify(const uint8_t* base, uint64_t mappedSize, const Table& t) {
  return verify(
      [&](uint64_t off, uint32_t& w) {
        if (off + 4 > mappedSize) return false;
        w = readWord(base + off);
        return true;
      },
      t);
}

int apply(uint8_t* base, const Config& cfg, const Table& t, Writer& w, std::vector<std::string>& log) {
  int n = 0;
  for (const auto& p : t.patches) {
    uint32_t v = std::clamp<uint32_t>(cfg.*(p.value), p.minValue, p.maxValue);
    uint32_t word = encode(p, v);
    if (w.write32(base + p.offset, word)) {
      ++n;
      log.push_back(std::string(p.name) + " = " + std::to_string(v) + " @" + hex(p.offset));
    } else {
      log.push_back(std::string("FAILED to write ") + p.name + " @" + hex(p.offset));
    }
  }
  return n;
}

int restore(uint8_t* base, const Table& t, Writer& w) {
  int n = 0;
  for (const auto& p : t.patches)
    if (w.write32(base + p.offset, p.original)) ++n;
  return n;
}

}  // namespace wp
