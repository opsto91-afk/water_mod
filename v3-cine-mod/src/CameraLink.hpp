// CameraLink.hpp - drives the game camera the same way the add-on does: with slash commands
// (camera / hud / fov_set / fade). This file only BUILDS the commands and decides WHEN to send
// them. Actually executing a command string inside Minecraft is the job of the "executor",
// which GameBridge plugs in once the game function is known (see GameBridge.cpp).
#pragma once
#include <functional>
#include <string>

#include "Core.hpp"

namespace cm {

class CameraLink {
 public:
  using Exec = std::function<bool(const std::string& command)>;

  void setExecutor(Exec e) { exec_ = std::move(e); }
  bool connected() const { return (bool)exec_; }

  // true: positions are written as "~x ~y ~z" (relative to the player). Use this while the
  // player's absolute position is unknown; the player must then stand still.
  void setRelative(bool r) { relative_ = r; }
  bool relative() const { return relative_; }

  void enter();                                  // cinematic mode ON  (hide HUD ...)
  void exit();                                   // cinematic mode OFF (give the camera back)
  void release();                                // give the camera back to the player (HUD stays hidden)
  void update(const Pose& pose, float dt);       // call every frame; sends at most ~20x per second
  void fade(float totalSeconds);                 // dip to black and back (half in, half out)

  int sent() const { return sent_; }
  int failed() const { return failed_; }

  // ---- pure helpers (unit-tested) ----
  static std::string easeCommandName(int easeIndex);               // "InOutCubic" -> "in_out_cubic"
  static std::string setCmd(const Pose& p, float easeSeconds, bool relative);
  static std::string fovCmd(float fov, float easeSeconds);
  static std::string fadeCmd(float totalSeconds);

 private:
  bool run(const std::string& cmd);

  Exec exec_;
  bool relative_ = true;
  bool active_ = false;
  bool havePose_ = false;
  Pose last_;
  float sinceSend_ = 1.f;
  float lastFov_ = -1.f;
  int sent_ = 0, failed_ = 0;
};

}  // namespace cm
