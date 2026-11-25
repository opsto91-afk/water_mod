// =============================================================================
// LocalPlayerComponent — RE findings from libminecraftpe.so (1.26.66.1, arm64)
// =============================================================================
// Source:  vft<LocalPlayer><8>  ->  slot[8] = 0xaf5d4d8 (file VA)
//          base      = 0x7699135000  (example launch)
//          runtime   = 0x76a40924d8
//
// Offsets found in disassembly:
//   [x0, #0xe38]  ->  ptr to player pose  (0x20 = 32 bytes, likely rotation+pos)
//   [x0, #0xe40]  ->  second pose ptr (0x20 = 32 bytes)
//   [x0, #0xca8]  ->  CameraComponent*  (MinecraftCamera::CameraComponent)
//   [x0, #0x1050] ->  flag (0x1 = firstPerson? 0x0 = thirdPerson?)
//   [x0, #0x1020] ->  prevPos  (vector3f)
//   [x0, #0x1018] ->  pose field (likely pitch/yaw/x/y/z)
//
// Params: x0 = this (LocalPlayer*)
// =============================================================================
#pragma once

#include <cstdint>

namespace Minecraft {

struct Pose {
    float x, y, z;      // position
    float pitch, yaw;   // rotation in degrees
    float fov = 70.f;
};

// LocalPlayerComponent offsets (ARM64, verified from slot[8] disassembly)
struct LocalPlayerOffsets {
    static constexpr int32_t kPoseOffset      = 0xe38;  // +0xe38  ->  Pose*
    static constexpr int32_t kPoseOffset2      = 0xe40;  // +0xe40  ->  Pose* (alt)
    static constexpr int32_t kCameraOffset     = 0xca8;  // +0xca8  ->  CameraComponent*
    static constexpr int32_t kFlagOffset       = 0x1050; // +0x1050 ->  uint32_t (firstPerson?)
    static constexpr int32_t kPrevPosOffset   = 0x1020; // +0x1020 ->  vec3f prevPos
    static constexpr int32_t kPoseFieldOffset = 0x1018; // +0x1018 ->  float[?] pitch/yaw/x/y/z
};

inline Pose readPlayerPose(void* localPlayer) {
    Pose out{};
    auto* pp = (Pose**)((uintptr_t)localPlayer + LocalPlayerOffsets::kPoseOffset);
    if (pp && *pp) {
        out.x = (*pp)->x;
        out.y = (*pp)->y;
        out.z = (*pp)->z;
        out.pitch = (*pp)->pitch;
        out.yaw = (*pp)->yaw;
    }
    return out;
}

// Read camera component
inline void* getCameraComponent(void* localPlayer) {
    return *(void**)((uintptr_t)localPlayer + LocalPlayerOffsets::kCameraOffset);
}

// Read prevPos
inline void readPrevPos(void* localPlayer, float out[3]) {
    auto* p = (float*)((uintptr_t)localPlayer + LocalPlayerOffsets::kPrevPosOffset);
    if (p) { out[0] = p[0]; out[1] = p[1]; out[2] = p[2]; }
}

} // namespace Minecraft
