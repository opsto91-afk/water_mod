// MyMod.cpp - "Screen POV" v1.5.0  (LeviLauncher native mod, Preloader SDK `pl::` API)
//
//   v1.5.0 = SAFE BUILD (2026-11-13) after the v1.4 on-device failure.
//   v1.4 overwrote a float3 inside two hooked functions that were misidentified
//   as the camera-pose writer. On device the camera never moved and the player
//   movement / attack controls were corrupted (joystick mirrored, actions reversed).
//   => v1.5.0 makes NO writes to game memory. The game is 100% vanilla.
//   The X / Y / Z settings below are SAVED but INERT - they are the values the
//   camera will use once the pose-consumer RE is finished and verified on device.
//
//   Mod Menu -> "Screen POV":
//     * Toggle "Screen POV"   - module on/off (logs only; nothing is applied in v1.5.0)
//     * "Offset X / Y / Z"    - the future camera offsets (saved, inert for now)
//     * "Relative to player"  - relative mode (camera follows player + offset) or
//                               absolute world XYZ. Saved, inert for now.
//     * "Reset to vanilla"    - offsets := 0, relative mode ON
//
//   Log: .../org.levimc.launcher/kurumi/pov_mod.log (fallback: the mod's data dir).
#include <pl/Mod.hpp>
#include <pl/ModMenu.hpp>

#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <system_error>
#include <vector>

#include <atomic>
#include <cmath>
#include <cstdio>

#include "PovBridge.hpp"
#include "PovCore.hpp"

namespace {

namespace fs = std::filesystem;
using namespace pov;

constexpr const char* kModuleId = "screen_pov";

struct Config {
  float px = 0.0f, py = 0.0f, pz = 0.0f;
  bool relative = true;
};

Config g_config;
std::mutex g_mu;

// ---- tiny key=value persistence --------------------------------------------------------
// v1.5.0 format: only pos_x / pos_y / pos_z / relative. Legacy orientation keys from
// v1.2-v1.4 config files are accepted and silently ignored (a warning is logged).
bool loadConfig(const fs::path& file, Config& out, std::vector<std::string>& warnings) {
  std::ifstream in(file);
  if (!in) return false;
  std::string line;
  auto trim = [](std::string s) {
    const auto hash = s.find('#');
    if (hash != std::string::npos) s = s.substr(0, hash);
    const size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return std::string();
    return s.substr(a, s.find_last_not_of(" \t\r\n") - a + 1);
  };
  bool any = false;
  while (std::getline(in, line)) {
    const size_t eq = line.find('=');
    if (eq == std::string::npos) continue;
    const std::string key = trim(line.substr(0, eq));
    const std::string value = trim(line.substr(eq + 1));
    if (key.empty()) continue;
    any = true;
    if (key == "pos_x") out.px = parseFloat(value, out.px);
    else if (key == "pos_y") out.py = parseFloat(value, out.py);
    else if (key == "pos_z") out.pz = parseFloat(value, out.pz);
    else if (key == "relative") out.relative = parseBool(value, out.relative);
    else if (key == "yaw" || key == "pitch" || key == "roll" ||
             key == "invert_yaw" || key == "invert_pitch")
      warnings.push_back("legacy key ignored (v1.5.0): " + key);
    else warnings.push_back("unknown key: " + key);
  }
  return any;
}

void saveConfig(const fs::path& file, const Config& c) {
  std::ofstream out(file, std::ios::trunc);
  // pos in blocks. These values are SAVED but inert until the camera override is fixed.
  out << "# Screen POV v1.5.0 (SAFE: override disabled) - pos in blocks\n"
      << "pos_x=" << c.px << "\n"
      << "pos_y=" << c.py << "\n"
      << "pos_z=" << c.pz << "\n"
      << "relative=" << (c.relative ? 1 : 0) << "\n";
}

class ScreenPovMod {
 public:
  static ScreenPovMod& instance() {
    static ScreenPovMod self;
    return self;
  }

  ScreenPovMod() : mSelf(*ll::mod::NativeMod::current()) {}

  [[nodiscard]] ll::mod::NativeMod& getSelf() const { return mSelf; }

  bool load() {
    std::error_code ec;
    mDir = mSelf.getDataDir();
    fs::create_directories(mDir, ec);
    mCfgFile = mDir / "pov.cfg";

    {
      std::lock_guard<std::mutex> lk(g_mu);
      std::vector<std::string> warnings;
      if (!fs::exists(mCfgFile, ec)) {
        saveConfig(mCfgFile, g_config);  // safe defaults on disk
      } else if (!loadConfig(mCfgFile, g_config, warnings)) {
        warnings.push_back("empty or unreadable pov.cfg - using defaults");
      }
      for (const auto& w : warnings) mSelf.getLogger().warn("{}", w);
    }

    applyRequestLocked();
    bridgeInit(mDir.string());
    mSelf.getLogger().info("Screen POV v1.5.0 SAFE MODE: camera override disabled; game is 100% vanilla");
    return true;
  }

  bool enable() {
    Config c;
    { std::lock_guard<std::mutex> lk(g_mu); c = g_config; }
    const bool ok =
        pl::modmenu::ModuleBuilder(kModuleId, "Screen POV")
            .modId(mSelf.getId())
            .description("v1.5.0 SAFE build: camera override DISABLED (see log). X/Y/Z are saved but inert. Log: kurumi/pov_mod.log")
            .defaultEnabled(false)
            .onToggle([](std::string_view, bool on) { ScreenPovMod::instance().onToggle(on); })
            .onConfigChanged([](std::string_view, std::string_view key, std::string_view value) {
              ScreenPovMod::instance().onConfig(key, value);
            })
            .config("pos_x", "Offset X (saved - inert in v1.5.0)",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.px), "-20", "20")
            .config("pos_y", "Offset Y (saved - inert in v1.5.0)",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.py), "-10", "10")
            .config("pos_z", "Offset Z (saved - inert in v1.5.0)",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.pz), "-20", "20")
            .config("relative", "Relative to player (off = world position)",
                    pl::modmenu::ConfigType::Toggle, c.relative ? "true" : "false")
            .config("reset_view", "Reset to vanilla (offsets 0, relative ON)", pl::modmenu::ConfigType::Button)
            .registerModule();
    mMenuRegistered.store(ok);
    flog(ok ? "enable: Mod Menu module registered" : "enable: Mod Menu registration FAILED");
    return true;
  }

  bool disable() {
    flog("disable");
    setOverrideEnabled(false);
    if (mMenuRegistered.exchange(false)) pl::modmenu::unregisterModule(kModuleId);
    return true;
  }

  bool unload() {
    setOverrideEnabled(false);
    bridgeShutdown();
    return true;
  }

 private:
  void flog(const std::string& s) {
    // The bridge keeps the authoritative log file; mirror to the mod logger too.
    mSelf.getLogger().info("{}", s);
  }

  void applyRequestLocked() {
    PovRequest req;
    req.px = g_config.px;
    req.py = g_config.py;
    req.pz = g_config.pz;
    req.relativePos = g_config.relative;
    setRequest(req);
  }

  void persistLocked() {
    saveConfig(mCfgFile, g_config);
  }

  void onToggle(bool on) {
    if (on) {
      std::lock_guard<std::mutex> lk(g_mu);
      applyRequestLocked();
    }
    setOverrideEnabled(on);  // OFF logs; nothing is applied either way in v1.5.0
  }

  void onConfig(std::string_view key, std::string_view value) {
    if (key == "reset_view") {
      {
        std::lock_guard<std::mutex> lk(g_mu);
        g_config.px = g_config.py = g_config.pz = 0.0f;
        g_config.relative = true;
        applyRequestLocked();
        persistLocked();
      }
      flog("menu: reset to vanilla (offsets 0, relative ON)");
      return;
    }
    {
      std::lock_guard<std::mutex> lk(g_mu);
      if (key == "pos_x") g_config.px = parseFloat(value, g_config.px);
      else if (key == "pos_y") g_config.py = parseFloat(value, g_config.py);
      else if (key == "pos_z") g_config.pz = parseFloat(value, g_config.pz);
      else if (key == "relative") g_config.relative = parseBool(value, g_config.relative);
      else return;
      applyRequestLocked();
      persistLocked();
    }
    flog(std::string("menu: ") + std::string(key) + " = " + std::string(value));
  }

  ll::mod::NativeMod& mSelf;
  fs::path mDir, mCfgFile;
  std::atomic<bool> mMenuRegistered{false};
};

}  // namespace

PL_REGISTER_MOD(ScreenPovMod, ScreenPovMod::instance())
