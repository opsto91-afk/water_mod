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
};

inline constexpr float kDeg2Rad = 3.14159265358979323846f / 180.f;
inline constexpr float kEyeHeight = 1.62f;  // feet -> eyes while standing

struct Vec3 { float x, y, z; };
struct Quat { float x, y, z, w; };

inline Vec3 cross(const Vec3& a, const Vec3& b) {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

// Pose (pitch + = look down, yaw 0 = south) -> MC camera quaternion.
// forward = (-sin y cos p, -sin p, cos y cos p); right = (-cos y, 0, -sin y);
// up = right x forward; matrix columns (right, up, -forward) -> quaternion.
inline Quat quatFromYawPitch(float yawDeg, float pitchDeg) {
  const float y = yawDeg * kDeg2Rad;
  const float p = pitchDeg * kDeg2Rad;
  const float sy = std::sin(y), cy = std::cos(y);
  const float sp = std::sin(p), cp = std::cos(p);
  const Vec3 right{-cy, 0.f, -sy};
  const Vec3 forward{-sy * cp, -sp, cy * cp};
  const Vec3 up = cross(right, forward);
  const float m00 = right.x, m01 = up.x, m02 = -forward.x;
  const float m10 = right.y, m11 = up.y, m12 = -forward.y;
  const float m20 = right.z, m21 = up.z, m22 = -forward.z;
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

// The override pose requested from the Mod Menu.
struct PovRequest {
  float yaw = 0;          // absolute MC yaw the camera should hold (degrees)
  float pitch = 0;        // absolute MC pitch (+ = look down)
  float px = 0, py = 0, pz = 0;   // position offset (relative mode) or absolute world position
  bool relativePos = true;        // true: pos sliders are added to the player's eye position
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
  out.yaw = req.yaw;
  out.pitch = req.pitch;
  return out;
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
