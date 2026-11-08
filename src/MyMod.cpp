// MyMod.cpp - "Water Tweaks" v0.2  (LeviLauncher native mod, Preloader SDK `pl::` API)
//
//   Mod Menu -> "Water Tweaks": toggle + sliders (water delay, lava delay x2, source count) + dry-run toggle.
//   * Patching is done by ONE worker thread, never inside a menu callback.
//   * It waits for libminecraftpe.so + 8 s of settle time, verifies the instructions, then writes them
//     with the SDK's pl::memory::writeBytes.
//   * Crash safety: an "armed" flag is created before the first write and removed after 10 s alive.
//     If the next start finds the flag, the previous run died -> SAFE MODE (no patching) until you
//     delete water_mod.disabled.
//   * Every step is appended (and fsync'ed) to  .../org.levimc.launcher/kurumi/water_mod.log
//     (fallback: the mod's data dir) so a crash still leaves a trail.
#include <fcntl.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include <pl/Mod.hpp>
#include <pl/ModMenu.hpp>
#include <pl/memory/Patch.hpp>

#include "WaterPatch.hpp"

namespace {

namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

constexpr const char* kModuleId = "water_tweaks";
constexpr const char* kModId = "water_mod";
constexpr const char* kLibName = "libminecraftpe.so";
constexpr const char* kKurumiLog = "/storage/emulated/0/Android/media/org.levimc.launcher/kurumi/water_mod.log";
constexpr auto kSettle = std::chrono::seconds(8);    // after the library shows up, wait before touching it
constexpr auto kSurvive = std::chrono::seconds(10);  // alive this long after patching => not a crash

struct LibMap {
  uintptr_t base = 0;
  uint64_t size = 0;
};

// First mapping (file offset 0) = base; the first LOAD segment has vaddr 0 => runtime = base + Zaphkiel address.
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
      if (off != 0) continue;
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

// Writes through the SDK (it validates the mapping, handles mprotect and the instruction cache).
struct SdkWriter : wp::Writer {
  bool write32(uint8_t* addr, uint32_t word) override {
    uint8_t b[4];
    std::memcpy(b, &word, 4);
    char name[64];
    std::snprintf(name, sizeof name, "water_mod.site.%p", static_cast<void*>(addr));
    return pl::memory::writeBytes(reinterpret_cast<uintptr_t>(addr), std::span<const uint8_t>(b, 4), name);
  }
};

bool truthy(std::string_view v) { return v == "1" || v == "true" || v == "True" || v == "on" || v == "yes"; }

uint32_t toUint(std::string_view v, uint32_t fallback) {
  std::string s(v);
  char* end = nullptr;
  double d = std::strtod(s.c_str(), &end);  // sliders may send "5" or "5.0"
  if (s.empty() || end == s.c_str() || d < 0) return fallback;
  return static_cast<uint32_t>(d + 0.5);
}

}  // namespace

class WaterMod {
 public:
  static WaterMod& instance() {
    static WaterMod i;
    return i;
  }

  WaterMod() : mSelf(*ll::mod::NativeMod::current()) {}
  ~WaterMod() {
    stopRegistration();
    stopWorker();
    if (mLogFd >= 0) ::close(mLogFd);
  }

  [[nodiscard]] ll::mod::NativeMod& getSelf() const { return mSelf; }

  bool load() {
    std::error_code ec;
    mDir = mSelf.getDataDir();
    fs::create_directories(mDir, ec);
    mCfgFile = mDir / "water.cfg";
    mArmedFlag = mDir / "water_mod.armed";
    mDisabledFlag = mDir / "water_mod.disabled";
    openLog();
    flog("---- load  (v0.2)  data dir: " + mDir.string());

    if (!fs::exists(mCfgFile, ec)) {
      std::ofstream out(mCfgFile);
      out << wp::defaultConfigText();
    }
    if (fs::exists(mArmedFlag, ec)) {  // the previous run created the flag but never cleared it
      fs::remove(mArmedFlag, ec);
      std::ofstream(mDisabledFlag) << "previous run crashed after patching\n";
      flog("PREVIOUS RUN DIED after patching -> SAFE MODE. Delete water_mod.disabled in the data dir to re-enable.");
    }
    mSafeMode = fs::exists(mDisabledFlag, ec);
    if (mSafeMode) flog("SAFE MODE is on: nothing will be written to game memory");
    mCfg = readConfigFile();
    return true;
  }

  bool enable() {
    flog("enable: Mod Menu registration scheduled after 3 seconds");
    startRegistration();
    startWorker();
    return true;
  }

  bool disable() {
    flog("disable");
    stopRegistration();
    {
      std::lock_guard<std::mutex> lk(mRegistrationMu);
      if (mMenuRegistered) {
        pl::modmenu::unregisterModule(kModuleId);
        mMenuRegistered = false;
      }
    }
    stopWorker();
    restoreNow();
    return true;
  }

  bool unload() {
    return disable();
  }

 private:
  void startRegistration() {
    stopRegistration();
    {
      std::lock_guard<std::mutex> lk(mRegistrationMu);
      mRegistrationStop = false;
    }
    mRegistrationThread = std::thread([this] {
      std::unique_lock<std::mutex> lk(mRegistrationMu);
      if (mRegistrationCv.wait_for(lk, std::chrono::seconds(3),
                                   [this] { return mRegistrationStop; })) {
        return;
      }

      const wp::Config c = snapshotCfg();
      const bool ok =
          pl::modmenu::ModuleBuilder(kModuleId, "Water Tweaks")
              .modId(kModId)
              .description("Water/lava speed + infinite-source rule. Log: kurumi/water_mod.log")
              .defaultEnabled(false)
              .onToggle([](std::string_view, bool on) { WaterMod::instance().onToggle(on); })
              .onConfigChanged([](std::string_view, std::string_view key, std::string_view value) {
                WaterMod::instance().onConfig(key, value);
              })
              .config("water_delay", "Water delay (ticks, vanilla 5)", pl::modmenu::ConfigType::SliderInt,
                      std::to_string(c.waterDelay), "1", "40")
              .config("lava_overworld", "Lava delay overworld (vanilla 30)", pl::modmenu::ConfigType::SliderInt,
                      std::to_string(c.lavaDelayOverworld), "1", "120")
              .config("lava_nether", "Lava delay nether (vanilla 10)", pl::modmenu::ConfigType::SliderInt,
                      std::to_string(c.lavaDelayNether), "1", "60")
              .config("source_count", "Sources for infinite water (vanilla 2)", pl::modmenu::ConfigType::SliderInt,
                      std::to_string(c.sourceCount), "1", "4")
              .config("dry_run", "Dry run (log only, no memory writes)", pl::modmenu::ConfigType::Toggle,
                      c.dryRun ? "true" : "false")
              .registerModule();
      mMenuRegistered = ok;
      lk.unlock();
      flog(ok ? "enable: module registered" : "enable: Mod Menu registration FAILED");
    });
  }

  void stopRegistration() {
    {
      std::lock_guard<std::mutex> lk(mRegistrationMu);
      mRegistrationStop = true;
    }
    mRegistrationCv.notify_all();
    if (mRegistrationThread.joinable()) mRegistrationThread.join();
  }

  // ---- logging (flushed every line so a crash leaves a trail) ----
  void openLog() {
    std::error_code ec;
    for (const std::string& p : {std::string(kKurumiLog), (mDir / "water_mod.log").string()}) {
      if (fs::exists(p, ec) && fs::file_size(p, ec) > 200 * 1024) fs::remove(p, ec);
      int fd = ::open(p.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
      if (fd >= 0) {
        mLogFd = fd;
        mLogPath = p;
        return;
      }
    }
  }

  void flog(const std::string& s) {
    std::lock_guard<std::mutex> lk(mLogMu);
    if (mLogFd >= 0) {
      char ts[16] = "--:--:--";
      std::time_t t = std::time(nullptr);
      std::tm tmv{};
      if (localtime_r(&t, &tmv)) std::strftime(ts, sizeof ts, "%H:%M:%S", &tmv);
      std::string line = std::string(ts) + " " + s + "\n";
      (void)!::write(mLogFd, line.data(), line.size());
      ::fsync(mLogFd);
    }
    mSelf.getLogger().info("{}", s);
  }

  // ---- config ----
  wp::Config readConfigFile() {
    wp::Config cfg;
    std::ifstream in(mCfgFile);
    std::stringstream ss;
    ss << in.rdbuf();
    std::vector<std::string> warnings;
    if (!wp::parseConfig(ss.str(), cfg, warnings)) flog("water.cfg empty/unreadable - using vanilla values");
    for (const auto& w : warnings) flog("water.cfg: " + w);
    return cfg;
  }

  void saveConfigFile(const wp::Config& c) {
    std::ofstream out(mCfgFile, std::ios::trunc);
    out << "# written by the Mod Menu sliders\n"
        << "water_delay=" << c.waterDelay << "\n"
        << "lava_delay_overworld=" << c.lavaDelayOverworld << "\n"
        << "lava_delay_nether=" << c.lavaDelayNether << "\n"
        << "infinite_source_count=" << c.sourceCount << "\n"
        << "dry_run=" << (c.dryRun ? 1 : 0) << "\n";
  }

  wp::Config snapshotCfg() {
    std::lock_guard<std::mutex> lk(mMu);
    return mCfg;
  }

  // ---- menu callbacks: only record the request, the worker does the real work ----
  void onToggle(bool on) {
    {
      std::lock_guard<std::mutex> lk(mMu);
      mWanted = on;
      mBad = false;  // toggling again retries verification
      mDirty = true;
      mLastChange = Clock::now();
    }
    flog(std::string("menu: module ") + (on ? "ON" : "OFF"));
    mCv.notify_all();
  }

  void onConfig(std::string_view key, std::string_view value) {
    wp::Config c;
    {
      std::lock_guard<std::mutex> lk(mMu);
      if (key == "water_delay") mCfg.waterDelay = toUint(value, mCfg.waterDelay);
      else if (key == "lava_overworld") mCfg.lavaDelayOverworld = toUint(value, mCfg.lavaDelayOverworld);
      else if (key == "lava_nether") mCfg.lavaDelayNether = toUint(value, mCfg.lavaDelayNether);
      else if (key == "source_count") mCfg.sourceCount = toUint(value, mCfg.sourceCount);
      else if (key == "dry_run") mCfg.dryRun = truthy(value);
      else return;
      c = mCfg;
      mDirty = true;
      mLastChange = Clock::now();
    }
    saveConfigFile(c);
    flog("menu: " + std::string(key) + " = " + std::string(value));
    mCv.notify_all();
  }

  // ---- worker ----
  void startWorker() {
    if (mThread.joinable()) return;
    mStop = false;
    mThread = std::thread([this] {
      try {
        run();
      } catch (const std::exception& e) {
        flog(std::string("worker exception: ") + e.what());
      } catch (...) {
        flog("worker unknown exception");
      }
    });
  }

  void stopWorker() {
    {
      std::lock_guard<std::mutex> lk(mMu);
      mStop = true;
    }
    mCv.notify_all();
    if (mThread.joinable()) mThread.join();
  }

  void run() {
    std::unique_lock<std::mutex> lk(mMu);
    while (!mStop) {
      mCv.wait_for(lk, std::chrono::milliseconds(250));
      if (mStop) break;
      if (mArmedAt != Clock::time_point{} && Clock::now() - mArmedAt > kSurvive) {
        std::error_code ec;
        fs::remove(mArmedFlag, ec);
        mArmedAt = Clock::time_point{};
        lk.unlock();
        flog("alive 10 s after patching -> crash flag cleared");
        lk.lock();
      }
      if (!mDirty) continue;
      if (Clock::now() - mLastChange < std::chrono::milliseconds(300)) continue;  // debounce slider drags
      mDirty = false;
      const bool wanted = mWanted;
      const wp::Config cfg = mCfg;
      lk.unlock();
      const bool retry = sync(wanted, cfg);
      lk.lock();
      if (retry) mDirty = true;
    }
  }

  // returns true when it should be tried again (library not ready yet)
  bool sync(bool wanted, const wp::Config& cfg) {
    if (!wanted) {
      restoreNow();
      return false;
    }
    if (mSafeMode) {
      flog("SAFE MODE: ignoring request (delete water_mod.disabled to re-enable)");
      return false;
    }
    {
      std::lock_guard<std::mutex> lk(mMu);
      if (mBad) return false;
    }
    LibMap lib;
    if (!findLib(lib)) {
      if (!mWaitingLogged) flog("waiting for libminecraftpe.so ...");
      mWaitingLogged = true;
      return true;
    }
    if (mLibSeenAt == Clock::time_point{}) {
      mLibSeenAt = Clock::now();
      flog("libminecraftpe.so found, base=0x" + hexs(lib.base) + " size=0x" + hexs(lib.size) + " - settling 8 s");
    }
    if (Clock::now() - mLibSeenAt < kSettle) return true;

    const uintptr_t base = lib.base;
    const std::string why = wp::verify(
        [base](uint64_t off, uint32_t& w) {
          auto v = pl::memory::readBytes(base + off, 4);  // SDK checks that the range is mapped + readable
          if (v.size() != 4) return false;
          std::memcpy(&w, v.data(), 4);
          return true;
        },
        wp::defaultTable());
    if (!why.empty()) {
      flog("NOT patching: " + why);
      std::lock_guard<std::mutex> lk(mMu);
      mBad = true;
      return false;
    }
    flog("verify OK: all guard/patch sites hold the expected instructions");

    if (cfg.dryRun) {
      flog("DRY RUN: would write water=" + std::to_string(cfg.waterDelay) + " lavaOW=" + std::to_string(cfg.lavaDelayOverworld) +
           " lavaNether=" + std::to_string(cfg.lavaDelayNether) + " sources=" + std::to_string(cfg.sourceCount) +
           "  (nothing written)");
      return false;
    }

    std::error_code ec;
    std::ofstream(mArmedFlag) << "armed\n";  // removed again after 10 s alive
    {
      std::lock_guard<std::mutex> lk(mMu);
      mArmedAt = Clock::now();
    }
    SdkWriter w;
    std::vector<std::string> lines;
    flog("writing patches ...");
    const int n = wp::apply(reinterpret_cast<uint8_t*>(base), cfg, wp::defaultTable(), w, lines);
    for (const auto& l : lines) flog("  " + l);
    mApplied = n > 0;
    flog("patched " + std::to_string(n) + " instruction(s)");
    return false;
  }

  void restoreNow() {
    std::lock_guard<std::mutex> lk(mApplyMu);
    if (!mApplied) return;
    LibMap lib;
    if (findLib(lib)) {
      SdkWriter w;
      const int n = wp::restore(reinterpret_cast<uint8_t*>(lib.base), wp::defaultTable(), w);
      flog("restored " + std::to_string(n) + " instruction(s) to vanilla");
    }
    mApplied = false;
    std::error_code ec;
    fs::remove(mArmedFlag, ec);
  }

  static std::string hexs(uint64_t v) {
    char b[24];
    std::snprintf(b, sizeof b, "%llx", static_cast<unsigned long long>(v));
    return b;
  }

  ll::mod::NativeMod& mSelf;
  fs::path mDir, mCfgFile, mArmedFlag, mDisabledFlag;
  int mLogFd = -1;
  std::string mLogPath;
  std::mutex mLogMu, mMu, mApplyMu, mRegistrationMu;
  std::condition_variable mCv, mRegistrationCv;
  std::thread mThread, mRegistrationThread;
  // guarded by mMu
  wp::Config mCfg;
  bool mWanted = false, mDirty = false, mStop = false, mBad = false;
  bool mRegistrationStop = false, mMenuRegistered = false;
  Clock::time_point mLastChange{}, mArmedAt{};
  // worker-thread only
  Clock::time_point mLibSeenAt{};
  bool mWaitingLogged = false;
  bool mApplied = false;
  bool mSafeMode = false;
};

PL_REGISTER_MOD(WaterMod, WaterMod::instance())
