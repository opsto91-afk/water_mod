// PovBridge.hpp - game side of the Screen POV mod.
//
//   v1.7.0 (POSITION OVERRIDE, 2026-11-13): the camera POSITION is overridden,
//   nothing else. Design facts (device-verified, see docs/freelook-re.md Phase 11):
//   * CamTick (fn_0xb7d4274) is never called on this game build - not hooked.
//   * CamSetter (fn_0xb7c7268) runs EVERY rendered frame with `this` == the LRP.
//   * UpdatePlayerFromCamera is never hooked (v1.4 skipped it - that was the bug).
//   * The ONLY write: 12 bytes at LRP+0x614 AFTER the camSetter original returns,
//     only while the module toggle is ON. Relative mode composes from a
//     disambiguated vanilla snapshot (no per-frame drift); absolute mode writes
//     the configured world XYZ verbatim. Orientation/InputComponent/movement code
//     is never touched.
//   * Toggle OFF => fully vanilla (zero writes).
//   Log: kurumi/pov_mod.log (fallback: the mod's data dir). Crash tracer included.
#pragma once
#include <string>

#include "PovCore.hpp"

namespace pov {

void bridgeInit(const std::string& dataDir);   // starts the watcher thread (idempotent)
void bridgeShutdown();                          // unhooks + stops the watcher thread

void setOverrideEnabled(bool on);               // module toggle (OFF = zero game writes)
bool overrideEnabled();
void setRequest(const PovRequest& req);         // store request (applied from camSetter detour)
PovRequest request();

bool hooksReady();                              // camSetter override hook installed?

// diagnostics (written into pov_mod.log + __android_log_print "ScreenPov")
void logDiagSnapshot();

}  // namespace pov
