// PovBridge.hpp - game side of the Screen POV mod.
//
//   Watcher thread finds libminecraftpe.so (60.4) and installs three hooks:
//     UpdatePlayerFromCamera (skipped while override ON)  -> the body never syncs from the camera
//     LocalPlayerApplyTurnDelta (dropped while override ON) -> mouse/touch never drags the view
//     RenderLevel (wrap-original)                          -> CameraComponent pose injection:
//        BEFORE orig: entt-registry walk finds the LocalPlayer's own CameraComponent
//                     (same algorithm as BedrockTools' InputComponent lookup), snapshot it,
//                     write our yaw/pitch/roll quaternion (+ view_offset when the probe confirms).
//        AFTER  orig: restore the snapshot byte-for-byte.
//   A one-time on-device probe discovers the component stride (the unit-quaternion invariant at
//   +0x28) and logs a full hexdump + all candidate strides to pov_mod.log.
//   Log: kurumi/pov_mod.log (fallback: the mod's data dir). Crash tracer dumps stage + fault addr.
#pragma once
#include <string>

#include "PovCore.hpp"

namespace pov {

void bridgeInit(const std::string& dataDir);   // starts the watcher thread (idempotent)
void bridgeShutdown();                          // unhooks + stops the watcher thread

void setOverrideEnabled(bool on);               // module toggle / quick button
bool overrideEnabled();
void setRequest(const PovRequest& req);         // called from the Mod Menu config callbacks
PovRequest request();

bool readPlayerEye(Pose& out);                  // live player eye pose (yaw/pitch from the game)
bool playerLinked();                            // is a valid player pointer known yet?
bool hooksReady();                              // did the two hooks install?

// diagnostics (written into pov_mod.log + __android_log_print "ScreenPov")
void logDiagSnapshot();

}  // namespace pov
