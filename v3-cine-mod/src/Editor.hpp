// Editor.hpp - V3 cinematic editor UI (ImGui only, no GL / Android calls).
//
// Layout (like a video editor):
//   * Movement / look are NOT drawn by us: touches that do not start on our UI go to the game
//     (its own keyboard & mouse controls, or the launcher's Pojav Controls overlay).
//   * Game preview fills the screen. Three small buttons toggle the panels:
//       [Editor] top-left   [Tools] top-right   [Timeline] bottom
//   * Panels sit next to the preview and can be resized by dragging the divider between them.
//   * Timeline: colored segment strips, key diamonds, "+" between segments for transitions,
//     and key points (pins) on their own row.
#pragma once
#include <functional>
#include <string>
#include <vector>

#include "Core.hpp"
#include "TouchRouter.hpp"
#include "imgui.h"

namespace cm {

// The host rasterises the font at this size; the editor scales it by screenHeight/540.
inline constexpr float kFontBasePx = 26.f;

enum class Screen { Main, Presets, Editor };
enum class CamMode { Free, Follow };

struct EditorHost {
  ImTextureID gameTex = nullptr;       // captured game frame (nullptr = none yet)
  int gameW = 0, gameH = 0;
  ImDrawCallback additiveCb = nullptr; // sets additive blending (GL only), may be null
  bool linkConnected = false;          // is a command executor plugged into the game?
  int cmdSent = 0, cmdFailed = 0;
  bool linkRelative = true;            // positions sent relative to the player
  std::function<void()> onClose;       // [X]
  std::function<void()> onSave;        // persist the library
  std::function<void(const Pose&, float)> onCamera;  // camera pose for the game (only while playing / scrubbing)
  std::function<bool(Pose&)> readPose;        // the player's real pose (false = not available yet)
  std::function<void()> onLive;               // give the camera back to the player
  std::function<void(float)> onFade;          // start a real fade (total seconds)
  std::function<void(bool)> onSetRelative;
};

struct ButtonRec {
  std::string label;
  ImVec2 mn, mx;
};

class Editor {
 public:
  Editor(Library& lib, TouchRouter& touch) : lib_(lib), touch_(touch) {}

  void draw(const ImVec2& display, float dt, EditorHost& host);

  // ---- state access (host-side tests) --------------------------------
  Screen screen() const { return screen_; }
  bool createOpen() const { return createOpen_; }
  bool transPopupOpen() const { return transOpen_; }
  int selectedSlot() const { return selSlot_; }
  int selectedKey() const { return selKey_; }
  int selectedSegment() const { return selSeg_; }
  int selectedTransition() const { return selTrans_; }
  int selectedPin() const { return selPin_; }
  float playhead() const { return playhead_; }
  void setPlayhead(float t) { playhead_ = t; }
  bool playing() const { return playing_; }
  CamMode camMode() const { return mode_; }
  const Pose& camera() const { return cam_; }
  void setCamera(const Pose& p) { cam_ = p; }
  bool poseLinked() const { return poseLinked_; }
  ImVec2 previewMin() const { return pvMin_; }
  ImVec2 previewMax() const { return pvMax_; }
  bool nightVision() const { return nightVision_; }
  bool fullBright() const { return fullBright_; }
  bool showEditor() const { return showEditor_; }
  bool showTools() const { return showTools_; }
  bool showTimeline() const { return showTimeline_; }
  float leftWidth() const { return leftW_; }
  float rightWidth() const { return rightW_; }
  float timelineHeight() const { return tlH_; }
  float lastFadeAlpha() const { return fadeA_; }
  const std::vector<ButtonRec>& buttons() const { return buttons_; }
  const ButtonRec* findButton(const std::string& label) const;

 private:
  enum class Icon { Moon, Sun };

  Preset* cur();
  void applyStyle(float ui);
  void tick(float dt, EditorHost& host);
  void toast(const char* msg);
  void clearSel();
  float keyTime(const Layout& L, int k) const;

  bool button(const char* label, bool enabled = true, bool accent = false);
  bool zoneBtn(ZoneId z, ImVec2 c, float r, Icon ic, bool on);
  bool toggleBtn(const char* id, const char* label, ImVec2 pos, float width, bool on);
  void splitter(const char* id, ImVec2 pos, ImVec2 size, bool vertical, float& valueBase, float dir, float ui,
                float minBase, float maxBase);
  void touchScroll(float ui);

  void drawToggles(const ImVec2& disp, float ui, float py1, EditorHost& host);
  void drawLeft(float x, float y, float w, float h, float ui, EditorHost& host);
  void drawPreview(float x, float y, float w, float h, float ui, EditorHost& host);
  void drawTools(float x, float y, float w, float h, float ui, EditorHost& host);
  void drawBottom(float x, float y, float w, float h, float ui);
  void drawTimeline(float ui);
  void drawCreatePopup(const ImVec2& disp, float ui);
  void drawTransitionPopup(const ImVec2& disp, float ui);

  void enterEditor();
  void addKey();
  void updateKey();
  void deleteKey();
  void follow();   // camera := sample(playhead)
  void goLive();   // camera back to the player

  Library& lib_;
  TouchRouter& touch_;
  EditorHost* host_ = nullptr;

  Screen screen_ = Screen::Main;
  CamMode mode_ = CamMode::Free;
  Pose cam_;

  // selection (only one of these is >= 0 at a time)
  int selKey_ = -1, selSeg_ = -1, selTrans_ = -1, selPin_ = -1;
  int selSlot_ = 0;

  bool playing_ = false;
  float playhead_ = 0.f, prevPlayhead_ = 0.f;
  float fadeA_ = 0.f;
  float zoom_ = 1.f, scroll_ = 0.f;

  // panels (all closed at start: the game is shown full screen)
  bool showEditor_ = false, showTools_ = false, showTimeline_ = false;
  float leftW_ = 230.f, rightW_ = 240.f, tlH_ = 196.f;  // base units (x ui = pixels)

  bool nightVision_ = false, fullBright_ = false;
  bool poseLinked_ = false;
  CamMode prevMode_ = CamMode::Free;
  ImVec2 pvMin_{0, 0}, pvMax_{0, 0};

  bool createOpen_ = false, createPending_ = false;
  bool transOpen_ = false, transPending_ = false;
  int transSeg_ = -1;
  ImVec2 transAnchor_{0, 0};
  bool popupOpen_ = false;
  char nameBuf_[48] = "";
  int catIdx_ = 0;
  float deleteArm_ = 0.f;

  std::string toast_;
  float toastT_ = 0.f;
  float styleUi_ = -1.f;
  std::vector<ButtonRec> buttons_;
};

}  // namespace cm
