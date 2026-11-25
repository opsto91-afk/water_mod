// Core.hpp - pure C++ data model + camera engine (no ImGui, no Android).
//
// Model (same idea as the HeroCine add-on):
//   N keys  ->  N-1 segments (key1->key2, key2->key3, ...).
//   Every segment has its own duration + ease type.
//   Between two segments you can put a transition: Instant, Hold (pause) or Fade (dip to black).
//   "Pins" (key points) are free reminders on the timeline, independent of the camera.
#pragma once
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace cm {

struct Pose {
  float x = 0, y = 64, z = 0;   // world position
  float pitch = 0, yaw = 0;     // degrees (Minecraft convention: pitch + = look down)
  float fov = 70;
};

// ---- easing (names + order are the same as the add-on's easeTypes list) ----
inline constexpr int kEaseCount = 32;
extern const char* const kEaseNames[kEaseCount];
float applyEase(int easeIndex, float t);  // t in [0,1] -> eased value (may overshoot for Back/Elastic/Spring)

// ---- transitions between two segments ----
enum class TransType : int { Instant = 0, Hold = 1, Fade = 2 };
extern const char* const kTransNames[3];
inline constexpr float kMaxTransition = 2.0f;  // seconds (same cap as the add-on)

struct Transition {
  TransType type = TransType::Instant;
  float duration = 0.f;  // seconds, 0..kMaxTransition
};

struct Segment {
  float duration = 5.f;  // seconds
  int ease = 0;          // index into kEaseNames
  Transition after;      // transition between THIS segment and the next one
};

struct Pin {
  float t = 0.f;         // seconds on the timeline
  std::string label;
};

struct Preset {
  std::string name = "Preset";
  std::string category = "Cinematic";
  std::vector<Pose> keys;       // key 1..N
  std::vector<Segment> segs;    // always max(0, keys.size()-1) entries
  std::vector<Pin> pins;

  int segmentCount() const { return (int)segs.size(); }

  int addKey(const Pose& p);                 // appends key N+1 (+ a new 5s segment); returns its index
  void setKey(int i, const Pose& p);         // re-set the pose of an existing key
  void removeKey(int i);                     // keeps the chain connected (merges neighbouring segments)
  void removeSegment(int i);                 // removes the segment and its end key
  int addPin(float t, const std::string& label);
  void removePin(int i);
  void normalise();                          // clamp values, keep segs.size()==keys-1
};

inline constexpr int kSlotCount = 3;

struct Library {
  std::optional<Preset> slots[kSlotCount];
};

extern const char* const kCategories[];
extern const int kCategoryCount;

float wrapAngle(float deg);  // -> (-180, 180]

// ---- timeline layout + sampling ----
struct SegTime {
  float start = 0;
  float end = 0;      // end of the camera move
  float holdEnd = 0;  // end of the Hold transition after this segment (== end when none)
};
struct Layout {
  std::vector<SegTime> seg;
  float total = 0;
};

Layout computeLayout(const Preset& p);
float presetTotal(const Preset& p);
Pose samplePreset(const Preset& p, float t);   // camera pose at time t
float fadeAlpha(const Preset& p, float t);     // 0..1 black overlay at time t (Fade transitions)

// ---- persistence (plain text, one file for all slots) ----
bool saveLibrary(const std::filesystem::path& file, const Library& lib);
bool loadLibrary(const std::filesystem::path& file, Library& lib);  // reads format 1 (old) and 2

}  // namespace cm
