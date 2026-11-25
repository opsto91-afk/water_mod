// =============================================================================
// CameraComponent + CameraOffset — RE findings from libminecraftpe.so
// =============================================================================
// RTTI confirmed (findclass output):
//   MinecraftCamera15CameraComponent       ->  0x76959239e0  (basic_storage add)
//   MinecraftCamera21CameraOffsetComponent ->  0x76959224b0  (basic_storage add)
//   MinecraftCamera21CameraLookAtComponent ->  0x7695921820  (basic_storage add)
//   MinecraftCamera26CameraFirstPersonComponent -> 0x76959226c0
//   MinecraftCamera26CameraThirdPersonComponent -> 0x7695922ed0
//
// From slot[8] disassembly:
//   x0 = LocalPlayer*
//   x0[#0xca8] = CameraComponent*
//   => CameraComponent at offset 0xca8 inside LocalPlayerComponent
//
// CameraComponent likely contains:
//   void* cameraEntity;        // the entity the camera follows/is
//   float pitch, yaw;           // camera orientation
//   float fov;
//   bool firstPerson;
//   bool thirdPerson;
//   EntityId lookAtEntity;
// =============================================================================
#pragma once
#include <cstdint>

namespace Minecraft {
struct CameraComponent {
    float pitch = 0.f;
    float yaw = 0.f;
    float fov = 70.f;
    bool  firstPerson = false;
    bool  thirdPerson = false;
    uint64_t lookAtEntity = 0;
};

// CameraOffsetComponent: offset from player eye position
struct CameraOffset {
    float x = 0.f, y = 0.f, z = 0.f;
};

// Accessors. NOTE: named cameraComponentAt() on purpose — LocalPlayerComponent.h
// already has a getCameraComponent() that returns the raw pointer, and two inline
// functions that differ only in return type would not compile in the same TU.
inline CameraComponent* cameraComponentAt(void* localPlayer) {
  return (CameraComponent*)((uintptr_t)localPlayer + 0xca8);
}
inline CameraOffset getCameraOffset(void* localPlayer) {
    // offset from lookAt entity + eye height
    float off[3] = {0,0,0};
    // TODO: read from CameraOffsetComponent via entt storage
    return {off[0], off[1], off[2]};
}
} // namespace Minecraft
