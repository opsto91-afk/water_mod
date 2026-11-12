// PovBridge.hpp - game side of the Screen POV mod.
//
//   Watcher thread finds libminecraftpe.so (60.4) and installs three hooks:
//     UpdatePlayerFromCamera (skipped while override ON)  -> the body never syncs from the camera
//     LocalPlayerApplyTurnDelta (pass-through)             -> normal look works
//     RenderLevel (wrap-original)                          -> camera POSITION injection:
//        BEFORE orig: resolve renderer+0x430 (mLevelRendererPlayer) -> LevelRendererPlayer,
//                     snapshot the float3 camera position at +0x614, write our XYZ
//                     (relative mode: live position + offset; fixed mode: absolute world XYZ).
//        AFTER  orig: restore the 12 bytes.
//   v1.2's entt/CameraComponent path wrote nothing on Android (log: writes=0, probe failed);
//   v1.3 writes straight into the renderer's own struct - verified readable on device.
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
bool hooksReady();                              // did the hooks install?

// diagnostics (written into pov_mod.log + __android_log_print "ScreenPov")
void logDiagSnapshot();

}  // namespace pov
