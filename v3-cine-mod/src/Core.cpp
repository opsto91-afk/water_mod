#include "Core.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>

namespace cm {

namespace {
constexpr float kPi = 3.14159265358979f;

float clamp01(float t) { return t < 0.f ? 0.f : (t > 1.f ? 1.f : t); }

float inPow(float t, int n) { return std::pow(t, (float)n); }
float outPow(float t, int n) { return 1.f - std::pow(1.f - t, (float)n); }
float inOutPow(float t, int n) {
  return t < 0.5f ? std::pow(2.f, (float)(n - 1)) * std::pow(t, (float)n)
                  : 1.f - std::pow(-2.f * t + 2.f, (float)n) / 2.f;
}

float bounceOut(float x) {
  const float n1 = 7.5625f, d1 = 2.75f;
  if (x < 1.f / d1) return n1 * x * x;
  if (x < 2.f / d1) { x -= 1.5f / d1; return n1 * x * x + 0.75f; }
  if (x < 2.5f / d1) { x -= 2.25f / d1; return n1 * x * x + 0.9375f; }
  x -= 2.625f / d1;
  return n1 * x * x + 0.984375f;
}

const float kC1 = 1.70158f, kC2 = kC1 * 1.525f, kC3 = kC1 + 1.f;
const float kC4 = 2.f * kPi / 3.f, kC5 = 2.f * kPi / 4.5f;

float inBack(float t) { return kC3 * t * t * t - kC1 * t * t; }
float outBack(float t) { return 1.f + kC3 * std::pow(t - 1.f, 3.f) + kC1 * std::pow(t - 1.f, 2.f); }
float inOutBack(float t) {
  return t < 0.5f ? (std::pow(2.f * t, 2.f) * ((kC2 + 1.f) * 2.f * t - kC2)) / 2.f
                  : (std::pow(2.f * t - 2.f, 2.f) * ((kC2 + 1.f) * (t * 2.f - 2.f) + kC2) + 2.f) / 2.f;
}
float inElastic(float t) { return t == 0.f ? 0.f : (t == 1.f ? 1.f : -std::pow(2.f, 10.f * t - 10.f) * std::sin((t * 10.f - 10.75f) * kC4)); }
float outElastic(float t) { return t == 0.f ? 0.f : (t == 1.f ? 1.f : std::pow(2.f, -10.f * t) * std::sin((t * 10.f - 0.75f) * kC4) + 1.f); }
float inOutElastic(float t) {
  if (t == 0.f) return 0.f;
  if (t == 1.f) return 1.f;
  return t < 0.5f ? -(std::pow(2.f, 20.f * t - 10.f) * std::sin((20.f * t - 11.125f) * kC5)) / 2.f
                  : (std::pow(2.f, -20.f * t + 10.f) * std::sin((20.f * t - 11.125f) * kC5)) / 2.f + 1.f;
}

struct EaseDef {
  const char* name;
  float (*fn)(float);
};

// Same order as the add-on's easeTypes[] (alphabetical, Linear first, Spring last).
const EaseDef kEases[kEaseCount] = {
    {"Linear", [](float t) { return t; }},
    {"InBack", inBack},
    {"InBounce", [](float t) { return 1.f - bounceOut(1.f - t); }},
    {"InCirc", [](float t) { return 1.f - std::sqrt(1.f - t * t); }},
    {"InCubic", [](float t) { return inPow(t, 3); }},
    {"InElastic", inElastic},
    {"InExpo", [](float t) { return t == 0.f ? 0.f : std::pow(2.f, 10.f * t - 10.f); }},
    {"InOutBack", inOutBack},
    {"InOutBounce", [](float t) { return t < 0.5f ? (1.f - bounceOut(1.f - 2.f * t)) / 2.f : (1.f + bounceOut(2.f * t - 1.f)) / 2.f; }},
    {"InOutCirc", [](float t) { return t < 0.5f ? (1.f - std::sqrt(1.f - std::pow(2.f * t, 2.f))) / 2.f : (std::sqrt(1.f - std::pow(-2.f * t + 2.f, 2.f)) + 1.f) / 2.f; }},
    {"InOutCubic", [](float t) { return inOutPow(t, 3); }},
    {"InOutElastic", inOutElastic},
    {"InOutExpo", [](float t) { return t == 0.f ? 0.f : (t == 1.f ? 1.f : (t < 0.5f ? std::pow(2.f, 20.f * t - 10.f) / 2.f : (2.f - std::pow(2.f, -20.f * t + 10.f)) / 2.f)); }},
    {"InOutQuad", [](float t) { return inOutPow(t, 2); }},
    {"InOutQuart", [](float t) { return inOutPow(t, 4); }},
    {"InOutQuint", [](float t) { return inOutPow(t, 5); }},
    {"InOutSine", [](float t) { return -(std::cos(kPi * t) - 1.f) / 2.f; }},
    {"InQuad", [](float t) { return inPow(t, 2); }},
    {"InQuart", [](float t) { return inPow(t, 4); }},
    {"InQuint", [](float t) { return inPow(t, 5); }},
    {"InSine", [](float t) { return 1.f - std::cos(t * kPi / 2.f); }},
    {"OutBack", outBack},
    {"OutBounce", bounceOut},
    {"OutCirc", [](float t) { return std::sqrt(1.f - std::pow(t - 1.f, 2.f)); }},
    {"OutCubic", [](float t) { return outPow(t, 3); }},
    {"OutElastic", outElastic},
    {"OutExpo", [](float t) { return t == 1.f ? 1.f : 1.f - std::pow(2.f, -10.f * t); }},
    {"OutQuad", [](float t) { return outPow(t, 2); }},
    {"OutQuart", [](float t) { return outPow(t, 4); }},
    {"OutQuint", [](float t) { return outPow(t, 5); }},
    {"OutSine", [](float t) { return std::sin(t * kPi / 2.f); }},
    // damped spring (approximation of the engine's Spring easing)
    {"Spring", [](float t) { return t >= 1.f ? 1.f : 1.f - std::exp(-6.f * t) * std::cos(t * 3.5f * kPi); }},
};
}  // namespace

const char* const kEaseNames[kEaseCount] = {
    "Linear", "InBack", "InBounce", "InCirc", "InCubic", "InElastic", "InExpo", "InOutBack",
    "InOutBounce", "InOutCirc", "InOutCubic", "InOutElastic", "InOutExpo", "InOutQuad", "InOutQuart",
    "InOutQuint", "InOutSine", "InQuad", "InQuart", "InQuint", "InSine", "OutBack", "OutBounce",
    "OutCirc", "OutCubic", "OutElastic", "OutExpo", "OutQuad", "OutQuart", "OutQuint", "OutSine", "Spring"};

float applyEase(int idx, float t) {
  t = clamp01(t);
  if (idx < 0 || idx >= kEaseCount) idx = 0;
  return kEases[idx].fn(t);
}

const char* const kTransNames[3] = {"Instant", "Hold", "Fade"};
const char* const kCategories[] = {"Cinematic", "Action", "Dialogue", "Establishing", "Chase", "Custom"};
const int kCategoryCount = (int)(sizeof(kCategories) / sizeof(kCategories[0]));

float wrapAngle(float d) {
  d = std::fmod(d, 360.f);
  if (d > 180.f) d -= 360.f;
  if (d <= -180.f) d += 360.f;
  return d;
}

// ------------------------------------------------------------------ Preset

void Preset::normalise() {
  const size_t want = keys.size() >= 2 ? keys.size() - 1 : 0;
  segs.resize(want);
  for (Segment& s : segs) {
    s.duration = std::clamp(s.duration, 0.1f, 3600.f);
    s.ease = std::clamp(s.ease, 0, kEaseCount - 1);
    s.after.duration = std::clamp(s.after.duration, 0.f, kMaxTransition);
    if (s.after.type == TransType::Instant) s.after.duration = 0.f;
  }
  for (Pin& p : pins) p.t = std::max(0.f, p.t);
}

int Preset::addKey(const Pose& p) {
  keys.push_back(p);
  if (keys.size() >= 2) segs.push_back(Segment{});
  return (int)keys.size() - 1;
}

void Preset::setKey(int i, const Pose& p) {
  if (i >= 0 && i < (int)keys.size()) keys[i] = p;
}

void Preset::removeKey(int i) {
  if (i < 0 || i >= (int)keys.size()) return;
  if (keys.size() <= 2) {
    // 1 or 2 keys: removing one leaves at most a single key and no segment
    keys.erase(keys.begin() + i);
    segs.clear();
    return;
  }
  if (i == 0) {
    keys.erase(keys.begin());
    segs.erase(segs.begin());
  } else if (i == (int)keys.size() - 1) {
    keys.pop_back();
    segs.pop_back();
  } else {
    // middle key: the two neighbouring segments merge into one (durations add up)
    segs[i - 1].duration += segs[i].duration;
    segs[i - 1].after = segs[i].after;
    segs.erase(segs.begin() + i);
    keys.erase(keys.begin() + i);
  }
  normalise();
}

void Preset::removeSegment(int i) {
  if (i < 0 || i >= (int)segs.size()) return;
  removeKey(i + 1);
}

int Preset::addPin(float t, const std::string& label) {
  Pin p;
  p.t = std::max(0.f, t);
  p.label = label.substr(0, 47);
  pins.push_back(p);
  return (int)pins.size() - 1;
}

void Preset::removePin(int i) {
  if (i >= 0 && i < (int)pins.size()) pins.erase(pins.begin() + i);
}

// ------------------------------------------------------------ layout/sample

Layout computeLayout(const Preset& p) {
  Layout L;
  float t = 0.f;
  for (size_t i = 0; i < p.segs.size(); ++i) {
    SegTime s;
    s.start = t;
    s.end = t + p.segs[i].duration;
    s.holdEnd = s.end;
    if (i + 1 < p.segs.size() && p.segs[i].after.type == TransType::Hold) s.holdEnd += p.segs[i].after.duration;
    L.seg.push_back(s);
    t = s.holdEnd;
  }
  L.total = t;
  return L;
}

float presetTotal(const Preset& p) { return computeLayout(p).total; }

Pose samplePreset(const Preset& p, float t) {
  if (p.keys.empty()) return Pose{};
  if (p.segs.empty() || t <= 0.f) return p.keys.front();
  const Layout L = computeLayout(p);
  for (size_t i = 0; i < L.seg.size(); ++i) {
    const SegTime& s = L.seg[i];
    if (t <= s.end) {
      const float span = s.end - s.start;
      const float u = span > 1e-6f ? (t - s.start) / span : 1.f;
      const float e = applyEase(p.segs[i].ease, u);
      const Pose& a = p.keys[i];
      const Pose& b = p.keys[i + 1];
      Pose o;
      o.x = a.x + (b.x - a.x) * e;
      o.y = a.y + (b.y - a.y) * e;
      o.z = a.z + (b.z - a.z) * e;
      o.pitch = std::clamp(a.pitch + (b.pitch - a.pitch) * e, -90.f, 90.f);
      o.yaw = wrapAngle(a.yaw + wrapAngle(b.yaw - a.yaw) * e);  // short way round
      o.fov = std::clamp(a.fov + (b.fov - a.fov) * e, 10.f, 120.f);
      return o;
    }
    if (t < s.holdEnd) return p.keys[i + 1];  // holding at the shared key
  }
  return p.keys.back();
}

float fadeAlpha(const Preset& p, float t) {
  const Layout L = computeLayout(p);
  float a = 0.f;
  for (size_t i = 0; i + 1 < p.segs.size(); ++i) {
    const Transition& tr = p.segs[i].after;
    if (tr.type != TransType::Fade || tr.duration <= 0.f) continue;
    const float h = tr.duration * 0.5f;
    a = std::max(a, clamp01(1.f - std::fabs(t - L.seg[i].end) / h));
  }
  return a;
}

// ------------------------------------------------------------------- save

static std::string clean(std::string s) {
  for (char& c : s)
    if (c == '\n' || c == '\r' || c == '\t') c = ' ';
  return s;
}

bool saveLibrary(const std::filesystem::path& file, const Library& lib) {
  std::error_code ec;
  if (file.has_parent_path()) std::filesystem::create_directories(file.parent_path(), ec);
  const std::filesystem::path tmp = file.string() + ".tmp";
  {
    std::ofstream f(tmp, std::ios::trunc);
    if (!f) return false;
    f << "CINEMOD 2\n";
    for (int i = 0; i < kSlotCount; ++i) {
      if (!lib.slots[i]) continue;
      const Preset& p = *lib.slots[i];
      char buf[256];
      f << "S " << i << "\nN " << clean(p.name) << "\nC " << clean(p.category) << "\n";
      for (const Pose& k : p.keys) {
        std::snprintf(buf, sizeof buf, "K %.6g %.6g %.6g %.6g %.6g %.6g\n", k.x, k.y, k.z, k.pitch, k.yaw, k.fov);
        f << buf;
      }
      for (const Segment& s : p.segs) {
        std::snprintf(buf, sizeof buf, "G %.6g %d %d %.6g\n", s.duration, s.ease, (int)s.after.type, s.after.duration);
        f << buf;
      }
      for (const Pin& pin : p.pins) {
        std::snprintf(buf, sizeof buf, "P %.6g ", pin.t);
        f << buf << clean(pin.label) << "\n";
      }
      f << "E\n";
    }
    if (!f.good()) return false;
  }
  std::filesystem::rename(tmp, file, ec);
  return !ec;
}

bool loadLibrary(const std::filesystem::path& file, Library& lib) {
  std::ifstream f(file);
  if (!f) return false;
  std::string line;
  if (!std::getline(f, line) || line.rfind("CINEMOD", 0) != 0) return false;
  const int version = std::atoi(line.c_str() + 7);
  if (version != 1 && version != 2) return false;

  struct OldKey { float t; Pose p; };
  Library out;
  int slot = -1;
  Preset cur;
  std::vector<OldKey> old;
  bool open = false;
  while (std::getline(f, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.empty()) continue;
    const char tag = line[0];
    const char* rest = line.size() > 2 ? line.c_str() + 2 : "";
    if (tag == 'S') {
      slot = std::atoi(rest);
      cur = Preset{};
      old.clear();
      open = slot >= 0 && slot < kSlotCount;
    } else if (!open) {
      continue;
    } else if (tag == 'N') {
      cur.name = rest;
    } else if (tag == 'C') {
      cur.category = rest;
    } else if (tag == 'K') {
      if (version == 1) {
        OldKey k;
        if (std::sscanf(rest, "%f %f %f %f %f %f %f", &k.t, &k.p.x, &k.p.y, &k.p.z, &k.p.pitch, &k.p.yaw, &k.p.fov) == 7) old.push_back(k);
      } else {
        Pose k;
        if (std::sscanf(rest, "%f %f %f %f %f %f", &k.x, &k.y, &k.z, &k.pitch, &k.yaw, &k.fov) == 6) cur.keys.push_back(k);
      }
    } else if (tag == 'G') {
      Segment s;
      int ease = 0, tt = 0;
      if (std::sscanf(rest, "%f %d %d %f", &s.duration, &ease, &tt, &s.after.duration) == 4) {
        s.ease = ease;
        s.after.type = (TransType)std::clamp(tt, 0, 2);
        cur.segs.push_back(s);
      }
    } else if (tag == 'P') {
      float t = 0;
      int n = 0;
      if (std::sscanf(rest, "%f %n", &t, &n) >= 1) cur.addPin(t, std::string(rest + n));
    } else if (tag == 'E') {
      if (version == 1) {  // old format: keys carried absolute times
        std::sort(old.begin(), old.end(), [](const OldKey& a, const OldKey& b) { return a.t < b.t; });
        for (size_t i = 0; i < old.size(); ++i) {
          cur.keys.push_back(old[i].p);
          if (i > 0) {
            Segment s;
            s.duration = std::max(0.5f, old[i].t - old[i - 1].t);
            cur.segs.push_back(s);
          }
        }
      }
      cur.normalise();
      out.slots[slot] = cur;
      open = false;
    }
  }
  lib = std::move(out);
  return true;
}

}  // namespace cm
