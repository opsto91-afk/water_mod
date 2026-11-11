// MyMod.cpp - "Screen POV" v1.0.0  (LeviLauncher native mod, Preloader SDK `pl::` API)
//
//   A small, standalone camera-only mod: set your own screen POV from the in-game Mod Menu.
//   Mod Menu -> "Screen POV":
//     * Toggle "Screen POV"   - master on/off. ON = the camera holds the pose set below.
//                               OFF = the camera goes back to the game (always the safe state).
//     * "Yaw (deg)"           - absolute MC yaw (0 south, 90 west, 180 north, 270 east)
//     * "Pitch (deg)"         - absolute MC pitch (+ looks down, - looks up)
//     * "Offset X / Y / Z"    - position offset relative to the player's eye (relative mode),
//                               or absolute world coordinates (relative mode off)
//     * "Relative to player"  - toggle between the two position modes
//     * "Snap to game view"   - copy the live game view into the override (yaw/pitch := current, offsets 0)
//     * "Live (release camera)" - hands the camera straight back to the game
//   Camera = screen POV only. The player's body is NEVER touched (no physics/rotation/movement).
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
  float yaw = 0;    // degrees
  float pitch = 0;  // degrees
  float px = 0.0f, py = 0.0f, pz = 0.0f;
  bool relative = true;
};

Config g_config;
std::mutex g_mu;

// ---- tiny key=value persistence --------------------------------------------------------
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
    if (key == "yaw") out.yaw = parseFloat(value, out.yaw);
    else if (key == "pitch") out.pitch = parseFloat(value, out.pitch);
    else if (key == "pos_x") out.px = parseFloat(value, out.px);
    else if (key == "pos_y") out.py = parseFloat(value, out.py);
    else if (key == "pos_z") out.pz = parseFloat(value, out.pz);
    else if (key == "relative") out.relative = parseBool(value, out.relative);
    else warnings.push_back("unknown key: " + key);
  }
  return any;
}

void saveConfig(const fs::path& file, const Config& c) {
  std::ofstream out(file, std::ios::trunc);
  out << "# Screen POV override values (yaw/pitch in degrees, pos in blocks)\n"
      << "yaw=" << c.yaw << "\n"
      << "pitch=" << c.pitch << "\n"
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
    return true;
  }

  bool enable() {
    Config c;
    { std::lock_guard<std::mutex> lk(g_mu); c = g_config; }
    const bool ok =
        pl::modmenu::ModuleBuilder(kModuleId, "Screen POV")
            .modId(mSelf.getId())
            .description("Camera-only POV set from here. Body never touched. Log: kurumi/pov_mod.log")
            .defaultEnabled(false)
            .onToggle([](std::string_view, bool on) { ScreenPovMod::instance().onToggle(on); })
            .onConfigChanged([](std::string_view, std::string_view key, std::string_view value) {
              ScreenPovMod::instance().onConfig(key, value);
            })
            .config("pov_yaw", "Yaw (deg) 0=south 90=west 180=north 270=east",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.yaw), "-180", "180")
            .config("pov_pitch", "Pitch (deg) + down / - up",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.pitch), "-90", "90")
            .config("pos_x", "Offset X (relative) / X (absolute)",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.px), "-20", "20")
            .config("pos_y", "Offset Y (relative) / Y (absolute)",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.py), "-10", "10")
            .config("pos_z", "Offset Z (relative) / Z (absolute)",
                    pl::modmenu::ConfigType::SliderFloat, std::to_string(c.pz), "-20", "20")
            .config("relative", "Relative to player (off = world position)",
                    pl::modmenu::ConfigType::Toggle, c.relative ? "true" : "false")
            .config("snap_view", "Snap to game view (copy live view)", pl::modmenu::ConfigType::Button)
            .config("live_release", "Live (release camera back to game)", pl::modmenu::ConfigType::Button)
            .registerModule();
    mMenuRegistered.store(ok);
    flog(ok ? "enable: Mod Menu module registered" : "enable: Mod Menu registration FAILED");
    return true;
  }

  bool disable() {
    flog("disable: releasing camera");
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
    req.yaw = g_config.yaw;
    req.pitch = g_config.pitch;
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
    setOverrideEnabled(on);  // OFF = camera fully back to the game
  }

  void onConfig(std::string_view key, std::string_view value) {
    if (key == "snap_view") {
      snapToGameView();
      return;
    }
    if (key == "live_release") {
      setOverrideEnabled(false);
      return;
    }
    {
      std::lock_guard<std::mutex> lk(g_mu);
      if (key == "pov_yaw") g_config.yaw = wrapAngle(parseFloat(value, g_config.yaw));
      else if (key == "pov_pitch") g_config.pitch = clampf(parseFloat(value, g_config.pitch), -90.f, 90.f);
      else if (key == "pos_x") g_config.px = parseFloat(value, g_config.px);
      else if (key == "pos_y") g_config.py = parseFloat(value, g_config.py);
      else if (key == "pos_z") g_config.pz = parseFloat(value, g_config.pz);
      else if (key == "relative") g_config.relative = parseBool(value, g_config.relative);
      else return;
      applyRequestLocked();
      persistLocked();
    }
    flog(std::string("menu: ") + std::string(key) + " = " + std::string(value));
  }

  // Copies the live game camera view into the override values (yaw/pitch sliders + zero offsets, relative mode).
  void snapToGameView() {
    Pose eye{};
    if (!readPlayerEye(eye)) {
      flog("snap: player view not readable yet - override left unchanged");
      return;
    }
    {
      std::lock_guard<std::mutex> lk(g_mu);
      g_config.yaw = wrapAngle(eye.yaw);
      g_config.pitch = clampf(eye.pitch, -90.f, 90.f);
      g_config.px = g_config.py = g_config.pz = 0.0f;
      g_config.relative = true;
      applyRequestLocked();
      persistLocked();
    }
    {
      std::lock_guard<std::mutex> lk(g_mu);
      flog("snap: override := live view (yaw=" + std::to_string(g_config.yaw) + " pitch=" + std::to_string(g_config.pitch) + ")");
    }
  }

  static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
  static float wrapAngle(float deg) {  // same convention as Pose: keep in (-180, 180]
    float d = std::fmod(deg + 180.f, 360.f);
    if (d < 0) d += 360.f;
    return d - 180.f;
  }

  ll::mod::NativeMod& mSelf;
  fs::path mDir, mCfgFile;
  std::atomic<bool> mMenuRegistered{false};
};

}  // namespace

PL_REGISTER_MOD(ScreenPovMod, ScreenPovMod::instance())
