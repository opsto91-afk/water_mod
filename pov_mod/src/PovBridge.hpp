// PovBridge.hpp - game side of the Screen POV mod.
//
//   v1.5.0 (SAFE BUILD, 2026-11-13): the game is NEVER written to.
//   Background: v1.4 wrapped two functions identified from on-device dumps
//   (fn_0xb7d4274 "CamTick" / fn_0xb7c7268 "CamSetter") and overwrote a float3 at
//   their `this+0x614` AFTER the original ran, on the theory that `this` was the
//   renderer's LevelRendererPlayer. On-device testing proved that theory WRONG:
//   the view never moved and the movement/attack controls were corrupted
//   (joystick inverted, actions reversed) - the writes landed in an object the
//   input/player-movement system consumes, not (only) the render camera pose.
// v1.5.0 design:
//   * NO writes of any kind to game memory.
//   * The ONLY hook is RenderLevel (same 60.4 signature Freelook uses), wrapped
//     100% pass-through: it reads the live camera float3 (renderer+0x430 ->
//     LRP+0x614) every ~2 s and logs it. Read-only, zero behaviour change.
//   * The module toggle and the X/Y/Z settings are saved but INERT.
//   * The camera override is DISABLED until the exact pose-consumer is
//     identified and verified on device.
//   Vanilla gameplay (movement, look, buttons) is 100% untouched.
//   Log: kurumi/pov_mod.log (fallback: the mod's data dir). Crash tracer included.
#pragma once
#include <string>

#include "PovCore.hpp"

namespace pov {

void bridgeInit(const std::string& dataDir);   // starts the watcher thread (idempotent)
void bridgeShutdown();                          // unhooks + stops the watcher thread

void setOverrideEnabled(bool on);               // module toggle (logs only in v1.5.0)
bool overrideEnabled();
void setRequest(const PovRequest& req);         // store request (inert in v1.5.0)
PovRequest request();

bool hooksReady();                              // RenderLevel diagnostic installed?

// diagnostics (written into pov_mod.log + __android_log_print "ScreenPov")
void logDiagSnapshot();

}  // namespace pov
