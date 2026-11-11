// PovBridge.hpp - game side of the Screen POV mod.
//
//   - A watcher thread finds libminecraftpe.so (60.4) and installs two hooks:
//       UpdatePlayerFromCamera (id 0x8c pattern)  -> AFTER the original: re-apply our quaternion
//       RenderLevel             (id 0x23 pattern)  -> BEFORE the original: write our eye position
//     Both writes are mincore()-guarded; the original functions are always called.
//   - The pose comes from the Mod Menu (pov_yaw / pov_pitch / pos_x / pos_y / pos_z / relative_pos)
//     multiplied against the player's live eye position when relative mode is on.
//   - Player identity: the player pointer captured from UpdatePlayerFromCamera (the ClientInstanceUpdate
//     signature never matched on 60.4). Falls back to a vtable probe if needed.
//   - Log: kurumi/pov_mod.log (fallback: the mod's data dir). Crash tracer dumps the stage + fault address.
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
