// Host-side unit tests for the PovCore math + parsing (no Android, no pl:: SDK).
#include "../src/PovCore.hpp"

#include <cmath>
#include <cstdio>
#include <cstdlib>

namespace {
int g_fail = 0;
void check(bool cond, const char* what) {
  if (cond) {
    std::printf("  ok   %s\n", what);
  } else {
    std::printf("  FAIL %s\n", what);
    ++g_fail;
  }
}
bool near(float a, float b, float eps = 1e-4f) { return std::fabs(a - b) <= eps; }
}  // namespace

int main() {
  using namespace pov;

  std::printf("-- parseFloat / parseBool --\n");
  check(near(parseFloat("1.25", -1.f), 1.25f), "parseFloat plain");
  check(near(parseFloat("-3", 0.f), -3.f), "parseFloat negative");
  check(near(parseFloat("5.0", 0.f), 5.f), "parseFloat trailing .0");
  check(near(parseFloat("abc", 7.f), 7.f), "parseFloat garbage falls back");
  check(near(parseFloat("", 2.f), 2.f), "parseFloat empty falls back");
  check(near(parseFloat("12x", 9.f), 9.f), "parseFloat trailing junk falls back");
  check(parseBool("true", false) == true, "parseBool true");
  check(parseBool("1", false) == true, "parseBool 1");
  check(parseBool("off", true) == false, "parseBool off");
  check(parseBool("junk", true) == true, "parseBool junk falls back");

  std::printf("-- computePose --\n");
  {
    PovRequest req;  // defaults: yaw 0 pitch 0, pos 0, relative
    Pose eye{10.f, 65.62f, 20.f, 45.f, 90.f};
    Pose out = computePose(req, eye, true);
    check(near(out.x, 10.f) && near(out.y, 65.62f) && near(out.z, 20.f), "relative mode with no offset = player eye");
    check(near(out.yaw, 0.f) && near(out.pitch, 0.f), "yaw/pitch passed through");
    req.px = 1.5f; req.py = 2.f; req.pz = -4.f;
    out = computePose(req, eye, true);
    check(near(out.x, 11.5f) && near(out.y, 67.62f) && near(out.z, 16.f), "relative mode applies offset");
    req.relativePos = false;
    out = computePose(req, eye, true);
    check(near(out.x, 1.5f) && near(out.y, 2.f) && near(out.z, -4.f), "absolute mode ignores player pos");
    req.relativePos = true;
    out = computePose(req, Pose{}, false);  // no player known -> falls back to absolute interpretation
    check(near(out.x, 1.5f) && near(out.y, 2.f) && near(out.z, -4.f), "relative mode falls back to raw sliders without player");
  }

  std::printf("-- quatFromYawPitch --\n");
  {
    auto len = [](const Quat& q) { return std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w); };
    for (float yaw : {-170.f, -90.f, -45.f, 0.f, 45.f, 90.f, 179.f}) {
      for (float pitch : {-80.f, -30.f, 0.f, 35.f, 89.f}) {
        const Quat q = quatFromYawPitch(yaw, pitch);
        if (!near(len(q), 1.f, 1e-3f)) {
          std::printf("  FAIL unit quaternion yaw=%g pitch=%g (len=%g)\n", yaw, pitch, len(q));
          ++g_fail;
        }
      }
    }
    std::printf("  ok   unit length across sweep\n");
    // Semantic check: q rotates the GL camera forward axis (0,0,-1) onto the world forward
    // derived from (yaw, pitch): forward = (-sin y cos p, -sin p, cos y cos p).
    auto rotate = [](const Quat& q, float vx, float vy, float vz, float out[3]) {
      // v' = q * v * q^-1 (q is unit length)
      const float tx = 2.f * (q.y * vz - q.z * vy);
      const float ty = 2.f * (q.z * vx - q.x * vz);
      const float tz = 2.f * (q.x * vy - q.y * vx);
      out[0] = vx + q.w * tx + q.y * tz - q.z * ty;
      out[1] = vy + q.w * ty + q.z * tx - q.x * tz;
      out[2] = vz + q.w * tz + q.x * ty - q.y * tx;
    };
    for (float yaw : {-120.f, -45.f, 0.f, 30.f, 90.f, 150.f}) {
      for (float pitch : {-55.f, 0.f, 25.f}) {
        const Quat q = quatFromYawPitch(yaw, pitch);
        const float y = yaw * 0.017453292519943295f, p = pitch * 0.017453292519943295f;
        const float fwd[3] = {-std::sin(y) * std::cos(p), -std::sin(p), std::cos(y) * std::cos(p)};
        float rot[3] = {};
        rotate(q, 0.f, 0.f, -1.f, rot);
        char what[128];
        std::snprintf(what, sizeof what, "yaw=%g pitch=%g camera forward maps to MC forward", yaw, pitch);
        check(near(rot[0], fwd[0], 1e-4f) && near(rot[1], fwd[1], 1e-4f) && near(rot[2], fwd[2], 1e-4f), what);
      }
    }
  }

  std::printf(g_fail == 0 ? "\nALL TESTS PASSED\n" : "\n%d TEST(S) FAILED\n", g_fail);
  return g_fail == 0 ? 0 : 1;
}
