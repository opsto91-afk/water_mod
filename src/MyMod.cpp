// MyMod.cpp - "Water Tweaks": LeviLauncher (Preloader SDK, `pl::` API) native mod.
//
//   Mod Menu -> module "Water Tweaks" (toggle)
//   ON  : waits for libminecraftpe.so, checks that the build is the one we analysed,
//         then patches 5 ARM64 instructions with the values from  <mod data dir>/water.cfg
//   OFF : puts the vanilla instructions back
//   Edit water.cfg, switch OFF then ON to apply new numbers.
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include <pl/Mod.hpp>
#include <pl/ModMenu.hpp>

#include "WaterPatch.hpp"

namespace {

constexpr const char* kModuleId = "water_tweaks";
constexpr const char* kModId = "water_mod";
constexpr const char* kLibName = "libminecraftpe.so";

struct LibMap {
  uintptr_t base = 0;
  uint64_t size = 0;
};

// The first mapping of the library (file offset 0) is its base; the first LOAD segment has vaddr 0,
// so  runtime address = base + (Zaphkiel address).
bool findLib(LibMap& out) {
  FILE* f = std::fopen("/proc/self/maps", "r");
  if (!f) return false;
  char line[1024];
  bool found = false;
  uintptr_t lo = 0, hi = 0;
  while (std::fgets(line, sizeof line, f)) {
    if (!std::strstr(line, kLibName)) continue;
    unsigned long a = 0, b = 0, off = 0;
    char perms[8] = {};
    if (std::sscanf(line, "%lx-%lx %7s %lx", &a, &b, perms, &off) != 4) continue;
    if (!found) {
      if (off != 0) continue;  // not the base mapping
      lo = a;
      hi = b;
      found = true;
    } else if (b > hi) {
      hi = b;
    }
  }
  std::fclose(f);
  if (!found) return false;
  out.base = lo;
  out.size = hi - lo;
  return true;
}

struct AndroidWriter : wp::Writer {
  bool write32(uint8_t* addr, uint32_t word) override {
    const uintptr_t pageSize = (uintptr_t)sysconf(_SC_PAGESIZE);
    const uintptr_t page = (uintptr_t)addr & ~(pageSize - 1);
    if (mprotect((void*)page, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return false;
    std::memcpy(addr, &word, 4);
    __builtin___clear_cache((char*)addr, (char*)addr + 4);
    mprotect((void*)page, pageSize, PROT_READ | PROT_EXEC);
    return true;
  }
};

}  // namespace

class WaterMod {
 public:
  static WaterMod& instance() {
    static WaterMod i;
    return i;
  }

  WaterMod() : mSelf(*ll::mod::NativeMod::current()) {}

  [[nodiscard]] ll::mod::NativeMod& getSelf() const { return mSelf; }

  bool load() {
    std::error_code ec;
    std::filesystem::create_directories(mSelf.getDataDir(), ec);
    mCfgFile = mSelf.getDataDir() / "water.cfg";
    if (!std::filesystem::exists(mCfgFile, ec)) {
      std::ofstream out(mCfgFile);
      out << wp::defaultConfigText();
    }
    return true;
  }

  bool enable() {
    const int generation = ++mRegistrationGeneration;
    std::thread([this, generation] {
      std::this_thread::sleep_for(std::chrono::seconds(3));
      registerMenuModule(generation);
    }).detach();
    mSelf.getLogger().info("{}", std::string("Water Tweaks enabled - Mod Menu registration scheduled"));
    return true;
  }

  bool disable() {
    ++mRegistrationGeneration;
    setActive(false);
    std::lock_guard<std::mutex> lock(mRegistrationMutex);
    if (mMenuRegistered) {
      pl::modmenu::unregisterModule(kModuleId);
      mMenuRegistered = false;
    }
    return true;
  }

  bool unload() { return disable(); }

 private:
  void registerMenuModule(int generation) {
    std::lock_guard<std::mutex> lock(mRegistrationMutex);
    if (mRegistrationGeneration.load() != generation || mMenuRegistered) return;

    const bool ok = pl::modmenu::ModuleBuilder(kModuleId, "Water Tweaks")
                        .modId(kModId)
                        .description("Water/lava tick delay and infinite-source rule. Edit water.cfg, toggle OFF/ON to apply.")
                        .defaultEnabled(false)
                        .onToggle([](std::string_view, bool enabled) { WaterMod::instance().setActive(enabled); })
                        .registerModule();
    if (!ok) {
      mSelf.getLogger().warn("Mod Menu module registration failed");
      return;
    }
    mMenuRegistered = true;
  }

  void log(const std::string& s) { mSelf.getLogger().info("{}", s); }

  wp::Config readConfig() {
    wp::Config cfg;
    std::ifstream in(mCfgFile);
    std::stringstream ss;
    ss << in.rdbuf();
    std::vector<std::string> warnings;
    if (!wp::parseConfig(ss.str(), cfg, warnings)) log("water.cfg empty or unreadable - using vanilla values");
    for (const auto& w : warnings) log("water.cfg: " + w);
    return cfg;
  }

  void setActive(bool on) {
    const int gen = ++mGen;
    if (on) {
      std::thread([this, gen] { applyWhenReady(gen); }).detach();
    } else {
      std::lock_guard<std::mutex> lock(mMu);
      if (!mApplied) return;
      LibMap lib;
      if (findLib(lib)) {
        AndroidWriter w;
        int n = wp::restore(reinterpret_cast<uint8_t*>(lib.base), wp::defaultTable(), w);
        log("restored " + std::to_string(n) + " instruction(s) to vanilla");
      }
      mApplied = false;
    }
  }

  // The game library may not be mapped yet when the mod loads: poll for up to two minutes.
  void applyWhenReady(int gen) {
    LibMap lib;
    for (int i = 0; i < 240; ++i) {
      if (mGen.load() != gen) return;  // toggled again meanwhile
      if (findLib(lib)) break;
      std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    if (lib.base == 0) {
      log("libminecraftpe.so not found - nothing patched");
      return;
    }
    std::lock_guard<std::mutex> lock(mMu);
    if (mGen.load() != gen) return;
    auto* base = reinterpret_cast<uint8_t*>(lib.base);
    const std::string why = wp::verify(base, lib.size, wp::defaultTable());
    if (!why.empty()) {
      log("NOT patching: " + why);
      return;
    }
    AndroidWriter w;
    std::vector<std::string> lines;
    int n = wp::apply(base, readConfig(), wp::defaultTable(), w, lines);
    for (const auto& l : lines) log(l);
    mApplied = n > 0;
    log("patched " + std::to_string(n) + " instruction(s)");
  }

  ll::mod::NativeMod& mSelf;
  std::filesystem::path mCfgFile;
  std::atomic<int> mGen{0};
  std::atomic<int> mRegistrationGeneration{0};
  std::mutex mRegistrationMutex;
  bool mMenuRegistered = false;
  std::mutex mMu;
  bool mApplied = false;
};

PL_REGISTER_MOD(WaterMod, WaterMod::instance())
