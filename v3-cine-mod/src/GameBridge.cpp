// =============================================================================
// GameBridge.cpp — the mod's only link to Minecraft's native code.
// =============================================================================
// What is here and what is NOT:
//
//   * CameraLink  : complete. Builds "/camera @s set ...", "/hud @s hide all",
//                   "/camera @s fade ..." and throttles them to ~20 Hz.
//   * Executor    : MISSING. No game function that runs a command string has been
//                   resolved yet, so CameraLink has no executor installed and the
//                   commands are only counted (sent / failed), not executed.
//   * Pose reader : MISSING. readPlayerPose() returns false until the LocalPlayer
//                   pointer is resolved by a hook.
//   * Hooks       : installCameraHooks() is a documented no-op (returns false).
//
// Reverse-engineering notes kept for whoever finishes this (see docs/NEXT_STEPS.md):
//   ClientInstance per-frame update : file VA 0x9b72978   (vtable slot[0], 1.26.66.1)
//   LocalPlayer                     : file VA 0xaf5d4d8   (vtable slot[8])
//   Camera setup                    : file VA 0xe830610
//   Command dispatch                : file VA 0xe804058
//   LocalPlayer offsets (arm64)     : +0xe38 pose ptr, +0xca8 CameraComponent*,
//                                     +0x1018 pose field, +0x1020 prevPos
// Runtime address = base of libminecraftpe.so + file VA (base changes every launch,
// so use the wildcard signatures in signatures.json, not the VAs).
//
// Everything below is written so that plugging the real executor in is a two-line change:
//   cm::game::link().setExecutor([](const std::string& cmd) { return Minecraft::runCommand(cmd); });
// =============================================================================
#include "GameBridge.hpp"

#include "CameraComponent.h"
#include "LocalPlayerComponent.h"

#include <atomic>
#include <mutex>

namespace cm::game {

namespace {
std::mutex g_mu;
std::atomic<bool> g_active{false};
Pose g_pose;

// Filled in by the (future) per-frame hook. Null until then.
void* g_localPlayerPtr = nullptr;
bool g_hooksInstalled = false;

CameraLink g_link;
}  // namespace

CameraLink& link() { return g_link; }

// ------------------------------------------------------------------ lifecycle
void onEnter() {
  // Cinematic mode on: hide the HUD and let the link start sending poses.
  g_link.enter();
}

void onExit() {
  // Cinematic mode off: give the camera and the HUD back to the player.
  g_link.exit();
  setCameraOverride(nullptr);
}

// ------------------------------------------------------------------ pose read
bool readPlayerPose(Pose& out) {
  void* player = nullptr;
  {
    std::lock_guard<std::mutex> l(g_mu);
    player = g_localPlayerPtr;
  }
  if (!player) return false;  // no hook yet -> editor keeps its own camera

  const Minecraft::Pose p = Minecraft::readPlayerPose(player);
  out.x = p.x;
  out.y = p.y;
  out.z = p.z;
  out.pitch = p.pitch;
  out.yaw = p.yaw;
  out.fov = p.fov;
  return true;
}

// ------------------------------------------------------------------ override
void setCameraOverride(const Pose* pose) {
  std::lock_guard<std::mutex> l(g_mu);
  g_active.store(pose != nullptr);
  if (pose) g_pose = *pose;
}

bool cameraOverride(Pose& out) {
  std::lock_guard<std::mutex> l(g_mu);
  if (g_active.load()) out = g_pose;
  return g_active.load();
}

// ------------------------------------------------------------------ hooks
bool installCameraHooks() {
  std::lock_guard<std::mutex> l(g_mu);
  if (g_hooksInstalled) return true;

  // TODO(camera): install the three hooks described at the top of this file.
  //   1) ClientInstance vtable slot[0] (signature "ClientInstance_per_frame")
  //      -> every frame: g_localPlayerPtr = <resolved LocalPlayer*>
  //   2) camera setup function ("CameraSetup")
  //      -> before the call: write g_pose into localPlayer[+0xca8] camera component
  //         (pitch / yaw / fov / position), after the call: restore the backup
  //   3) command dispatch ("CommandDispatch")
  //      -> g_link.setExecutor(...) so /camera and /hud actually run
  //
  // Until then the UI, timeline, segments, transitions and pins all work, but the
  // game camera is not driven.
  return false;
}

}  // namespace cm::game
