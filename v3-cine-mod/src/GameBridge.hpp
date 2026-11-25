// GameBridge.hpp - the single place where the C++ side talks to Minecraft's native code.
//
// Right now the camera ENGINE (timeline -> pose, ease, fade) and the COMMAND BUILDER
// (CameraLink: "/camera @s set ...", "/hud @s hide all", "/camera @s fade ...") are complete.
// What is still missing is the EXECUTOR: a resolved game function that runs a command string,
// and the player-pose reader. Both live behind the functions below so that the rest of the mod
// does not have to change once they are implemented.
//
// Status of each entry point:
//   link()             - CameraLink instance, command timing (works, but nothing executes yet)
//   onEnter()/onExit() - called when the Mod Menu module is switched on / off
//   readPlayerPose()   - returns false until the LocalPlayer pointer is resolved by a hook
//   setCameraOverride()- stores / clears the cinematic pose for the (future) camera hook
//   cameraOverride()   - reads it back
//   installCameraHooks()- installs the game hooks; currently a documented no-op
#pragma once

#include "CameraLink.hpp"
#include "Core.hpp"

namespace cm::game {

// Command link (slash-command builder + send timing). Set an executor on it to make it live.
CameraLink& link();

// Mod Menu module switched on / off.
void onEnter();
void onExit();

// The player's real pose (POV keys). Returns false while the LocalPlayer pointer is unknown.
bool readPlayerPose(Pose& out);

// Cinematic pose override used by the (future) camera hook.
void setCameraOverride(const Pose* pose);
bool cameraOverride(Pose& out);

// Install the game-side hooks (ClientInstance per-frame, camera setup). Safe to call twice.
// Returns true when the hooks are actually in place; false means "not implemented yet".
bool installCameraHooks();

}  // namespace cm::game
