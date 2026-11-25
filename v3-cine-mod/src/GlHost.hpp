// GlHost.hpp - Android/GLES side: draws the ImGui editor on top of the game every frame
// and shows the (captured) game frame inside the preview box.
//
// Everything here runs on the game's render thread (inside the eglSwapBuffers detour),
// except the on*() input functions which may be called from any thread.
#pragma once
#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Core.hpp"
#include "Editor.hpp"
#include "TouchRouter.hpp"

namespace cm {

class GlHost {
 public:
  struct Callbacks {
    std::function<void()> onSave;              // persist presets
    std::function<void()> onClose;             // [X] pressed
    std::function<void(const Pose&, float)> onCamera;  // new camera pose for the game
    std::function<void(float)> onFade;                 // real screen fade (total seconds)
    std::function<bool(Pose&)> readPose;               // the player's real pose (empty = not available yet)
    std::function<void()> onLive;                      // give the camera back to the player
    std::function<void(bool)> onSetRelative;           // relative / absolute positions
    std::function<void(EditorHost&)> decorate;         // fill link status shown in Tools
    std::function<Pose()> startPose;                   // where the editor camera starts each time the mode is switched on
    std::function<void(bool)> onKeyboard;      // show / hide the Android soft keyboard
  };

  static GlHost& instance();

  void configure(Library* lib, Callbacks cb);

  void setActive(bool on) { active_.store(on); }
  bool active() const { return active_.load(); }

  // Ask the render thread to destroy ImGui + GL objects (it must be done there).
  void requestShutdown() { shutdownReq_.store(true); }
  void clearShutdown() {   // call when (re-)enabling the mod
    shutdownReq_.store(false);
    shutdownDone_.store(false);
  }
  bool waitShutdown(int timeoutMs);

  // Called from the eglSwapBuffers detour BEFORE the real swap.
  void onSwap(void* eglDisplay, void* eglSurface);

  // Input, any thread. Return true when the event was consumed by our UI (game must not see it).
  bool onTouch(int action, int pointerId, float x, float y);
  bool onKey(int androidKeyCode, unsigned unicodeChar, bool down);
  bool onText(const std::string& utf8);

 private:
  bool initImGui();
  void shutdownNow();
  void ensureTexture(int w, int h);

  std::atomic<bool> active_{false};
  std::atomic<bool> shutdownReq_{false};
  std::atomic<bool> shutdownDone_{false};

  Library* lib_ = nullptr;
  Callbacks cb_;
  TouchRouter touch_;
  std::unique_ptr<Editor> editor_;

  bool inited_ = false;
  bool wasActive_ = false;
  bool kbShown_ = false;
  void* surface_ = nullptr;
  long long surfaceArea_ = 0;
  unsigned tex_ = 0;
  int texW_ = 0, texH_ = 0;
  long long lastNs_ = 0;

  std::mutex inMu_;
  struct KeyEv {
    int key;
    bool down;
  };
  std::vector<KeyEv> keyQ_;
  std::string textQ_;
  bool textSeen_ = false;
};

}  // namespace cm
