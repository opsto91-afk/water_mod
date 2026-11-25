// TouchRouter.hpp - decides which touches belong to our ImGui UI and which go to the GAME.
//
//   * A finger that STARTS on one of our UI areas (panels, dividers, buttons, popups) is consumed
//     and drives ImGui as the mouse pointer (one finger at a time).
//   * A finger that starts anywhere else is NOT consumed: the game (and whatever controls sit on
//     top of it, e.g. the launcher's Pojav Controls overlay) receives it untouched.
//   * Every finger keeps its owner until it lifts, so multi-touch works: one finger can drag a
//     divider while another one plays the game.
#pragma once
#include <array>
#include <mutex>
#include <vector>

#include "imgui.h"

namespace cm {

// Small round buttons drawn on the preview (they are zones, not ImGui widgets).
enum ZoneId { ZNight, ZBright, ZCount };

struct TouchEv {
  int action = 0;  // Android MotionEvent action (low byte is used)
  int id = 0;      // pointer id
  float x = 0, y = 0;
};

class TouchRouter {
 public:
  // Input thread. Returns true = consumed by our UI, false = the game keeps the event.
  bool offer(const TouchEv& e);

  // Render thread ---------------------------------------------------------
  void beginFrame(ImGuiIO& io);   // call BEFORE ImGui::NewFrame()
  void releaseAll(ImGuiIO& io);   // call when the editor closes

  // The editor registers its areas while drawing; they take effect next frame.
  void beginZones();
  void setZone(ZoneId z, ImVec2 mn, ImVec2 mx);
  void setBlock(ImVec2 mn, ImVec2 mx);    // any area of our ImGui UI that must swallow touches

  bool held(ZoneId z) const { return held_[z]; }
  bool takeClick(ZoneId z);               // true once per tap on a zone
  bool zoneRect(ZoneId z, ImVec2& mn, ImVec2& mx) const;  // tests / debugging

 private:
  struct Rect {
    ImVec2 mn{0, 0}, mx{0, 0};
    bool active = false;
  };
  struct Ptr {
    int id = -1;
    int owner = -1;  // ZoneId, kOwnUI
    ImVec2 pos{0, 0};
  };
  static constexpr int kOwnUI = 100;

  static bool inside(const Rect& r, ImVec2 p) {
    return r.active && p.x >= r.mn.x && p.x <= r.mx.x && p.y >= r.mn.y && p.y <= r.mx.y;
  }
  bool hitUi(ImVec2 p) const;    // caller holds mu_
  int hitZone(ImVec2 p) const;   // render thread

  // shared (guarded by mu_)
  mutable std::mutex mu_;
  std::vector<TouchEv> queue_;
  std::vector<int> ownedIds_;                 // fingers consumed by the UI
  std::array<Rect, ZCount> pubZones_{};
  std::vector<Rect> pubBlocks_;

  // render thread only
  std::array<Rect, ZCount> cur_{}, next_{};
  std::vector<Rect> nextBlocks_;
  std::vector<Ptr> ptrs_;
  std::array<bool, ZCount> owned_{};
  std::array<bool, ZCount> held_{};
  std::array<bool, ZCount> click_{};
  int uiPtr_ = -1;
};

}  // namespace cm
