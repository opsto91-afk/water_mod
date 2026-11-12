// PovBridge.hpp - game side of the Screen POV mod.
//
//   Watcher thread finds libminecraftpe.so (60.4) and installs five hooks:
//     UpdatePlayerFromCamera (skipped while override ON)  -> the body never syncs from the camera
//     LocalPlayerApplyTurnDelta (pass-through)             -> normal look works
//     RenderLevel (wrap-original, diagnostic only)         -> logs the live camera position
//     CamTick    (fn_0xb7d4274, Zaphkiel)                  -> per-frame POSE writer hook:
//        the original runs first (vanilla pose is written as usual), then - only while the
//        override is ON - the 12-byte position float3 at LRP+0x614 is overwritten
//        (relative mode: live pose + offset; fixed mode: absolute world XYZ).
//     CamSetter  (fn_0xb7c7268, Zaphkiel)                  -> same post-orig overwrite, covers the
//        immediate pose-set (packet/script) path.
//   v1.3 injected BEFORE the original RenderLevel and the CamTick then overwrote +0x614 with
//   vanilla's eased pose - the view never moved. v1.4 writes AFTER the writer runs, so the
//   renderer always consumes OUR position while the override is ON.
//   Vanilla re-writes +0x614 every tick, so OFF needs no restore logic.
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
