// WaterPatch.hpp - pure logic (no Android / preloader dependency, so it is unit-testable on a PC).
//
// Every patch site is ONE 32-bit ARM64 instruction inside libminecraftpe.so (offset == ELF virtual
// address == Zaphkiel address; the first LOAD segment starts at vaddr 0). Before touching anything
// we check that the site still holds the instruction we saw in Zaphkiel; if not (different
// Minecraft version) NOTHING is written.
#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace wp {

struct Config {
  uint32_t waterDelay = 5;            // game ticks between water updates       (vanilla 5)
  uint32_t lavaDelayOverworld = 30;   // lava, normal dimensions                (vanilla 30)
  uint32_t lavaDelayNether = 10;      // lava, "ultra warm" dimensions          (vanilla 10)
  uint32_t sourceCount = 2;           // adjacent sources needed for a new one  (vanilla 2)
  bool dryRun = false;                // true = verify + log only, never write memory
};

// Parses "key=value" lines ('#' starts a comment). Unknown keys / bad values are reported in `warnings`.
// Returns false only if nothing could be parsed at all.
bool parseConfig(const std::string& text, Config& out, std::vector<std::string>& warnings);
std::string defaultConfigText();

enum class Kind { MovzW, CmpWImm };  // mov wN,#imm16  /  cmp wN,#imm12

struct Patch {
  const char* name;
  uint64_t offset;      // from libminecraftpe.so base
  uint32_t original;    // instruction word expected there (vanilla)
  Kind kind;
  uint32_t reg;         // Rd for MovzW, Rn for CmpWImm
  uint32_t Config::*value;
  uint32_t minValue, maxValue;
};

struct Guard {  // extra instruction that must match, used only to recognise the right build
  uint64_t offset;
  uint32_t expected;
};

struct Table {
  std::vector<Patch> patches;
  std::vector<Guard> guards;
};

const Table& defaultTable();  // the real offsets (Minecraft build analysed with Zaphkiel)

uint32_t encodeMovzW(uint32_t rd, uint32_t imm16);
uint32_t encodeCmpWImm(uint32_t rn, uint32_t imm12);
uint32_t encode(const Patch& p, uint32_t value);

// Writes one 32-bit word at `addr` (the Android implementation does mprotect + cache flush).
struct Writer {
  virtual ~Writer() = default;
  virtual bool write32(uint8_t* addr, uint32_t word) = 0;
};

// Reads the 32-bit instruction at library offset `off`; returns false if that memory is not readable.
using ReadWord = std::function<bool(uint64_t off, uint32_t& word)>;

// "" = ok, otherwise a human-readable reason why we must not patch.
std::string verify(const ReadWord& read, const Table& t);
std::string verify(const uint8_t* base, uint64_t mappedSize, const Table& t);  // plain-pointer version (tests)
// Patches every site to the values in `cfg` (values are clamped to the allowed range).
// Returns the number of sites written; `log` receives one line per site.
int apply(uint8_t* base, const Config& cfg, const Table& t, Writer& w, std::vector<std::string>& log);
// Puts every site back to its vanilla instruction.
int restore(uint8_t* base, const Table& t, Writer& w);

}  // namespace wp
