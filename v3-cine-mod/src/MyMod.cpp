// =============================================================================
// MyMod.cpp — mod entry point for the LeviLauncher preloader SDK (pl:: API).
// =============================================================================
// What this file does:
//   1. registers the mod with the preloader           (PL_REGISTER_MOD)
//   2. registers the "V3 Cine Mod" Mod Menu module    (on/off switch)
//   3. hooks eglSwapBuffers so the ImGui editor can draw on top of the game
//   4. forwards touch + key events to the editor (touch routing decides what the game keeps)
//
// It does NOT drive the game camera: that needs the game-side hooks that are still
// missing, see GameBridge.cpp / docs/NEXT_STEPS.md.
// =============================================================================
#include <EGL/egl.h>
#include <dlfcn.h>

#include <filesystem>
#include <string>
#include <thread>
#include <utility>

#include <pl/Input.hpp>
#include <pl/Mod.hpp>
#include <pl/ModMenu.hpp>
#include <pl/memory/Hook.hpp>

#include "Core.hpp"
#include "GameBridge.hpp"
#include "GlHost.hpp"

namespace {

constexpr const char *kModuleId = "cinematic_mod.mode";
constexpr const char *kModuleName = "V3 Cine Mod";

using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
SwapFn g_origSwap = nullptr;

// Called on the game's render thread for every frame, before the buffer is presented.
EGLBoolean swapDetour(EGLDisplay display, EGLSurface surface) {
  cm::GlHost::instance().onSwap(display, surface);
  return g_origSwap ? g_origSwap(display, surface) : EGL_FALSE;
}

bool g_inputRegistered = false;

}  // namespace

class CinematicMod {
public:
  static CinematicMod &instance() {
    static CinematicMod i;
    return i;
  }

  // pl::mod::detail::ScopedCurrentMod() sets the current mod while PLGetModRegistration()
  // runs, so NativeMod::current() is valid here.
  CinematicMod() : mSelf(*ll::mod::NativeMod::current()) {}

  [[nodiscard]] ll::mod::NativeMod &getSelf() const { return mSelf; }

  bool load() {
    auto &self = getSelf();
    std::error_code ec;
    std::filesystem::create_directories(self.getDataDir(), ec);
    mLibFile = self.getDataDir() / "presets.txt";
    if (cm::loadLibrary(mLibFile, mLib))
      self.getLogger().info("loaded presets from {}", mLibFile.string());
    else
      self.getLogger().info("no presets yet ({})", mLibFile.string());
    return true;
  }

  bool enable() {
    auto &self = getSelf();
    auto &host = cm::GlHost::instance();

    // ---------------------------------------------------------------- callbacks
    cm::GlHost::Callbacks cb;
    cb.onSave = [this] { saveLibrary(); };
    cb.onClose = [] {
      cm::GlHost::instance().setActive(false);
      cm::game::onExit();
      std::thread([] { pl::modmenu::setModuleEnabled(kModuleId, false); }).detach();
    };
    cb.onCamera = [](const cm::Pose &p, float dt) {
      cm::game::setCameraOverride(&p);
      cm::game::link().update(p, dt);
    };
    cb.onFade = [](float seconds) { cm::game::link().fade(seconds); };
    cb.onLive = [] { cm::game::setCameraOverride(nullptr); };
    cb.readPose = [](cm::Pose &p) { return cm::game::readPlayerPose(p); };
    cb.onSetRelative = [](bool rel) { cm::game::link().setRelative(rel); };
    cb.startPose = [] {
      cm::Pose p;
      p.x = 0.f;
      p.y = 1.62f;
      p.z = 0.f;
      p.pitch = 0.f;
      p.yaw = 0.f;
      p.fov = 70.f;
      return p;
    };
    cb.decorate = [](cm::EditorHost &h) {
      const auto &l = cm::game::link();
      h.linkConnected = l.connected();
      h.cmdSent = l.sent();
      h.cmdFailed = l.failed();
      h.linkRelative = l.relative();
    };
    cb.onKeyboard = [](bool show) {
      std::thread([show] {
        if (show)
          pl::input::showKeyboard();
        else
          pl::input::hideKeyboard();
      }).detach();
    };

    host.configure(&mLib, std::move(cb));
    host.clearShutdown();
    host.setActive(false);  // the Mod Menu switch turns the editor on

    // ------------------------------------------------------------- Mod Menu entry
    registerModule();

    // -------------------------------------------------------------- game-side hooks
    if (!cm::game::installCameraHooks())
      self.getLogger().warn("camera hooks not installed (not implemented yet) - "
                            "the editor works, the game camera is not driven");

    // ---------------------------------------------------------------- input
    if (!g_inputRegistered) {
      g_inputRegistered = true;
      pl::input::registerTouchCallback([](const pl::input::TouchEvent &e) {
        return cm::GlHost::instance().onTouch(e.action, e.pointerId, e.x, e.y);
      });
      pl::input::registerKeyCallback([](const pl::input::KeyEvent &e) {
        return cm::GlHost::instance().onKey(e.keyCode, e.unicodeChar, e.isKeyDown);
      });
    }

    // ------------------------------------------------- hook eglSwapBuffers
    if (mSwapHook.installed()) return true;  // already hooked (enable called twice)

    void *target = dlsym(RTLD_DEFAULT, "eglSwapBuffers");
    if (!target) {
      self.getLogger().error("eglSwapBuffers not found");
      return false;
    }
    mSwapHook = pl::memory::HookHandle(
        target, reinterpret_cast<pl::memory::FuncPtr>(&swapDetour),
        reinterpret_cast<pl::memory::FuncPtr *>(&g_origSwap));
    if (!mSwapHook.installed()) {
      self.getLogger().error("failed to hook eglSwapBuffers");
      return false;
    }
    self.getLogger().info("eglSwapBuffers hooked");
    return true;
  }

  bool disable() {
    auto &host = cm::GlHost::instance();
    host.setActive(false);
    cm::game::onExit();
    saveLibrary();

    // ImGui + GL objects must be destroyed on the render thread (inside the detour).
    host.requestShutdown();
    host.waitShutdown(500);

    if (mSwapHook.installed()) mSwapHook.reset();
    return true;
  }

private:
  void saveLibrary() {
    std::error_code ec;
    std::filesystem::create_directories(mLibFile.parent_path(), ec);
    if (cm::saveLibrary(mLibFile, mLib))
      getSelf().getLogger().info("saved presets to {}", mLibFile.string());
  }

  void registerModule() {
    if (mModuleRegistered) return;
    auto &self = getSelf();
    mModuleRegistered = pl::modmenu::ModuleBuilder(kModuleId, kModuleName)
                            .description("Cinematic camera editor (presets, keys, "
                                         "segments, transitions, timeline)")
                            .modId(self.getId())
                            .defaultEnabled(false)
                            .onToggle([](std::string_view, bool enabled) {
                              auto &host = cm::GlHost::instance();
                              host.setActive(enabled);
                              if (enabled)
                                cm::game::onEnter();
                              else
                                cm::game::onExit();
                            })
                            .registerModule();
    if (!mModuleRegistered)
      self.getLogger().error("could not register Mod Menu module {}", kModuleId);
  }

  ll::mod::NativeMod &mSelf;
  std::filesystem::path mLibFile;
  pl::memory::HookHandle mSwapHook;
  cm::Library mLib;
  bool mModuleRegistered = false;
};

// The preloader looks up PLGetModRegistration(); this macro exports it.
PL_REGISTER_MOD(CinematicMod, CinematicMod::instance())
