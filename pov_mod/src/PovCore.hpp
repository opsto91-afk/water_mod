// PovCore.hpp - pure data model + camera math for the Screen POV mod.
// Header-only, no Android / no pl:: dependencies so host unit tests can compile it as-is.
#pragma once
#include <cmath>
#include <cstdlib>
#include <string>
#include <string_view>

namespace pov {

struct Pose {
  float x = 0, y = 64, z = 0;   // world position (eye level)
  float pitch = 0, yaw = 0;     // degrees (Minecraft: pitch + = look down, yaw 0 = south)
  float roll = 0;               // degrees; + = right wing down (DABR convention)
};

inline constexpr float kDeg2Rad = 3.14159265358979323846f / 180.f;
inline constexpr float kEyeHeight = 1.62f;  // feet -> eyes while standing

struct Vec3 { float x, y, z; };
struct Quat { float x, y, z, w; };

inline Vec3 cross(const Vec3& a, const Vec3& b) {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

// Rodrigues' rotation of v around unit/normal axis k by a (radians).
inline Vec3 rotateAxis(const Vec3& v, const Vec3& k, float a) {
  const float c = std::cos(a), s = std::sin(a);
  const Vec3 kxv = cross(k, v);
  const float kdotv = k.x * v.x + k.y * v.y + k.z * v.z;
  return {v.x * c + kxv.x * s + k.x * kdotv * (1.f - c),
          v.y * c + kxv.y * s + k.y * kdotv * (1.f - c),
          v.z * c + kxv.z * s + k.z * kdotv * (1.f - c)};
}

// Rotation matrix from the MC camera basis (yaw/pitch/roll) as GLM-style columns, packed into a
// quaternion. Layout matches Do-A-Barrel-Roll: forward f = (-sin y cos p, -sin p, cos y cos p),
// right r = (-cos y, 0, -sin y), up u = r x f, columns (r, u, -f). Roll rotates r and u around f.
inline Quat quatFromBasis(const Vec3& right, const Vec3& up, const Vec3& negForward) {
  const float m00 = right.x, m01 = up.x, m02 = negForward.x;
  const float m10 = right.y, m11 = up.y, m12 = negForward.y;
  const float m20 = right.z, m21 = up.z, m22 = negForward.z;
  Quat q{};
  const float tr = m00 + m11 + m22;
  if (tr > 0.f) {
    const float s = std::sqrt(tr + 1.f) * 2.f;
    q.w = 0.25f * s; q.x = (m21 - m12) / s; q.y = (m02 - m20) / s; q.z = (m10 - m01) / s;
  } else if (m00 > m11 && m00 > m22) {
    const float s = std::sqrt(1.f + m00 - m11 - m22) * 2.f;
    q.w = (m21 - m12) / s; q.x = 0.25f * s; q.y = (m01 + m10) / s; q.z = (m02 + m20) / s;
  } else if (m11 > m22) {
    const float s = std::sqrt(1.f + m11 - m00 - m22) * 2.f;
    q.w = (m02 - m20) / s; q.x = (m01 + m10) / s; q.y = 0.25f * s; q.z = (m12 + m21) / s;
  } else {
    const float s = std::sqrt(1.f + m22 - m00 - m11) * 2.f;
    q.w = (m10 - m01) / s; q.x = (m02 + m20) / s; q.y = (m12 + m21) / s; q.z = 0.25f * s;
  }
  const float len = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
  if (len > 1e-6f) { q.x /= len; q.y /= len; q.z /= len; q.w /= len; }
  return q;
}

// Pose (pitch + = look down, yaw 0 = south, roll + = right wing down) -> MC camera quaternion.
inline Quat quatFromYawPitchRoll(float yawDeg, float pitchDeg, float rollDeg) {
  const float y = yawDeg * kDeg2Rad;
  const float p = pitchDeg * kDeg2Rad;
  const Vec3 right{-std::cos(y), 0.f, -std::sin(y)};
  const Vec3 forward{-std::sin(y) * std::cos(p), -std::sin(p), std::cos(y) * std::cos(p)};
  Vec3 up = cross(right, forward);
  Vec3 r = right;
  if (rollDeg != 0.f) {
    const float a = rollDeg * kDeg2Rad;
    r = rotateAxis(r, forward, a);
    up = rotateAxis(up, forward, a);
  }
  const Vec3 negForward{-forward.x, -forward.y, -forward.z};
  return quatFromBasis(r, up, negForward);
}

// Pose (pitch + = look down, yaw 0 = south) -> MC camera quaternion.
inline Quat quatFromYawPitch(float yawDeg, float pitchDeg) {
  return quatFromYawPitchRoll(yawDeg, pitchDeg, 0.f);
}

// The override pose requested from the Mod Menu.
struct PovRequest {
  float yaw = 0;          // absolute MC yaw the camera should hold (degrees)
  float pitch = 0;        // absolute MC pitch (+ = look down)
  float roll = 0;         // absolute roll (+ = right wing down)
  float px = 0, py = 0, pz = 0;   // position offset (relative mode) or absolute world position
  bool relativePos = true;        // true: pos sliders are added to the player's eye position
  bool invertYaw = false;         // device-test aid: flip the sign of the applied yaw
  bool invertPitch = false;       // device-test aid: flip the sign of the applied pitch
};

// Build the final world-space eye pose for an override request.
//   playerEye: the player's eye position (feet + kEyeHeight), if known; otherwise falls back to
//              the raw sliders interpreted as absolute world position.
//   hasPlayer: whether playerEye is valid.
inline Pose computePose(const PovRequest& req, const Pose& playerEye, bool hasPlayer) {
  Pose out{};
  if (req.relativePos && hasPlayer) {
    out.x = playerEye.x + req.px;
    out.y = playerEye.y + req.py;
    out.z = playerEye.z + req.pz;
  } else {
    out.x = req.px;
    out.y = req.py;
    out.z = req.pz;
  }
  out.yaw = req.invertYaw ? -req.yaw : req.yaw;
  out.pitch = req.invertPitch ? -req.pitch : req.pitch;
  out.roll = req.roll;  // roll is not flipped (it has its own slider sign convention)
  return out;
}

// Compose the camera position to write into the renderer's pose float3 (LRP+0x614).
//   relativePos=true : live vanilla pose + (px,py,pz) offset
//   relativePos=false: absolute world XYZ (px,py,pz), player ignored
inline void composeCamPos(const PovRequest& req, const float cur[3], float out[3]) {
  if (req.relativePos) {
    out[0] = cur[0] + req.px;
    out[1] = cur[1] + req.py;
    out[2] = cur[2] + req.pz;
  } else {
    out[0] = req.px;
    out[1] = req.py;
    out[2] = req.pz;
  }
}

// Safe float parse used by config callbacks (invalid strings keep the old value).
inline float parseFloat(std::string_view s, float fallback) {
  // from_chars would be ideal; the mod menu values are simple "1.25"/"-3" strings.
  char buf[64] = {};
  if (s.empty() || s.size() >= sizeof buf) return fallback;
  for (size_t i = 0; i < s.size(); ++i) buf[i] = s[i];
  char* end = nullptr;
  const float v = std::strtof(buf, &end);
  if (end == buf || *end != '\0') return fallback;
  return v;
}

inline bool parseBool(std::string_view s, bool fallback) {
  if (s == "true" || s == "1" || s == "on" || s == "yes") return true;
  if (s == "false" || s == "0" || s == "off" || s == "no") return false;
  return fallback;
}

}  // namespace pov
