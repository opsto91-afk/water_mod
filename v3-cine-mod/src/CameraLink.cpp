#include "CameraLink.hpp"

#include <cctype>
#include <cmath>
#include <cstdio>

namespace cm {

std::string CameraLink::easeCommandName(int idx) {
  if (idx < 0 || idx >= kEaseCount) idx = 0;
  const std::string src = kEaseNames[idx];
  std::string out;
  for (size_t i = 0; i < src.size(); ++i) {
    const char c = src[i];
    if (std::isupper((unsigned char)c)) {
      if (i > 0) out += '_';
      out += (char)std::tolower((unsigned char)c);
    } else {
      out += c;
    }
  }
  return out;
}

static std::string num(float v, bool rel) {
  char b[32];
  std::snprintf(b, sizeof b, rel ? "~%.3f" : "%.3f", v);
  return b;
}

std::string CameraLink::setCmd(const Pose& p, float ease, bool rel) {
  char b[256];
  std::snprintf(b, sizeof b, "camera @s set minecraft:free ease %.3f linear pos ", ease);
  std::string s = b;
  s += num(p.x, rel) + " " + num(p.y, rel) + " " + num(p.z, rel);
  std::snprintf(b, sizeof b, " rot %.3f %.3f", p.pitch, p.yaw);
  return s + b;
}

std::string CameraLink::fovCmd(float fov, float ease) {
  char b[96];
  std::snprintf(b, sizeof b, "camera @s fov_set %.3f %.3f linear", fov, ease);
  return b;
}

std::string CameraLink::fadeCmd(float total) {
  const float h = std::fmax(0.05f, total * 0.5f);
  char b[96];
  std::snprintf(b, sizeof b, "camera @s fade time %.3f 0 %.3f", h, h);
  return b;
}

bool CameraLink::run(const std::string& cmd) {
  if (!exec_) return false;
  const bool ok = exec_(cmd);
  ok ? ++sent_ : ++failed_;
  return ok;
}

void CameraLink::enter() {
  active_ = true;
  havePose_ = false;
  lastFov_ = -1.f;
  sinceSend_ = 1.f;
  run("hud @s hide all");
}

void CameraLink::exit() {
  if (!active_) return;
  active_ = false;
  run("camera @s clear");
  run("camera @s fov_clear 0.15 linear");
  run("hud @s reset all");
}

void CameraLink::release() {
  if (!active_) return;
  run("camera @s clear");
  havePose_ = false;
  lastFov_ = -1.f;
  sinceSend_ = 1.f;
}

void CameraLink::update(const Pose& p, float dt) {
  if (!active_ || !exec_) return;
  sinceSend_ += dt;
  const bool changed = !havePose_ || std::fabs(p.x - last_.x) > 1e-3f || std::fabs(p.y - last_.y) > 1e-3f ||
                       std::fabs(p.z - last_.z) > 1e-3f || std::fabs(p.pitch - last_.pitch) > 1e-3f ||
                       std::fabs(p.yaw - last_.yaw) > 1e-3f;
  if (changed && sinceSend_ >= 0.05f) {
    run(setCmd(p, 0.06f, relative_));  // 20 Hz updates, the game smooths between them
    last_ = p;
    havePose_ = true;
    sinceSend_ = 0.f;
  }
  // The add-on avoids re-sending an unchanged FOV (it fights the camera's own interpolation).
  if (std::fabs(p.fov - lastFov_) > 0.05f && havePose_) {
    run(fovCmd(p.fov, 0.08f));
    lastFov_ = p.fov;
  }
}

void CameraLink::fade(float total) {
  if (active_) run(fadeCmd(total));
}

}  // namespace cm
