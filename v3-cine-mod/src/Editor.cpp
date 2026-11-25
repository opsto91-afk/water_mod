#include "Editor.hpp"

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace cm {

namespace {
constexpr float kPi = 3.14159265358979f;
inline ImVec2 V(float x, float y) { return ImVec2(x, y); }

const ImU32 kAccent = IM_COL32(112, 82, 250, 235);
const ImU32 kDisc = IM_COL32(18, 22, 38, 205);
const ImU32 kText = IM_COL32(225, 228, 240, 255);
const ImU32 kDim = IM_COL32(140, 146, 170, 255);
// Space (base units) kept free at the right screen edge for the launcher's floating button.
constexpr float kRightInset = 80.f;
// Segment strips cycle white -> green -> blue (like the clips of a video editor).
const ImU32 kStrip[3] = {IM_COL32(236, 238, 244, 255), IM_COL32(64, 172, 100, 255), IM_COL32(70, 124, 228, 255)};
const ImU32 kStripText[3] = {IM_COL32(20, 22, 34, 255), IM_COL32(255, 255, 255, 255), IM_COL32(255, 255, 255, 255)};

const ImGuiWindowFlags kPanel = ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoCollapse |
                                ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoSavedSettings |
                                ImGuiWindowFlags_NoBringToFrontOnFocus;

void fmtTime(float t, char* out, size_t n) {
  if (t < 0) t = 0;
  const int m = (int)(t / 60.f);
  std::snprintf(out, n, "%02d:%05.2f", m, t - m * 60.f);
}
}  // namespace

const ButtonRec* Editor::findButton(const std::string& label) const {
  for (const ButtonRec& b : buttons_)
    if (b.label == label) return &b;
  return nullptr;
}

Preset* Editor::cur() {
  if (selSlot_ < 0 || selSlot_ >= kSlotCount || !lib_.slots[selSlot_]) return nullptr;
  return &*lib_.slots[selSlot_];
}

void Editor::toast(const char* msg) {
  toast_ = msg;
  toastT_ = 2.2f;
}

void Editor::clearSel() { selKey_ = selSeg_ = selTrans_ = selPin_ = -1; }

float Editor::keyTime(const Layout& L, int k) const {
  if (k <= 0) return 0.f;
  if (k - 1 < (int)L.seg.size()) return L.seg[k - 1].end;
  return L.total;
}

void Editor::applyStyle(float ui) {
  ImGuiStyle& s = ImGui::GetStyle();
  s = ImGuiStyle();
  ImGui::StyleColorsDark(&s);
  s.WindowRounding = 6.f;
  s.FrameRounding = 6.f;
  s.GrabRounding = 6.f;
  s.PopupRounding = 6.f;
  s.WindowBorderSize = 1.f;
  s.FrameBorderSize = 0.f;
  s.ItemSpacing = V(8.f, 7.f);
  s.WindowPadding = V(8.f, 8.f);
  s.FramePadding = V(8.f, 6.f);
  s.GrabMinSize = 16.f;
  ImVec4* c = s.Colors;
  c[ImGuiCol_WindowBg] = ImVec4(0.035f, 0.045f, 0.085f, 0.97f);
  c[ImGuiCol_PopupBg] = ImVec4(0.05f, 0.06f, 0.11f, 0.99f);
  c[ImGuiCol_Border] = ImVec4(0.16f, 0.18f, 0.30f, 1.f);
  c[ImGuiCol_Button] = ImVec4(0.09f, 0.11f, 0.19f, 1.f);
  c[ImGuiCol_ButtonHovered] = ImVec4(0.16f, 0.16f, 0.34f, 1.f);
  c[ImGuiCol_ButtonActive] = ImVec4(0.38f, 0.28f, 0.90f, 1.f);
  c[ImGuiCol_FrameBg] = ImVec4(0.08f, 0.10f, 0.17f, 1.f);
  c[ImGuiCol_Header] = ImVec4(0.22f, 0.17f, 0.52f, 0.85f);
  c[ImGuiCol_HeaderHovered] = ImVec4(0.30f, 0.22f, 0.68f, 0.90f);
  c[ImGuiCol_SliderGrab] = ImVec4(0.44f, 0.32f, 0.98f, 1.f);
  c[ImGuiCol_SliderGrabActive] = ImVec4(0.58f, 0.46f, 1.f, 1.f);
  c[ImGuiCol_CheckMark] = ImVec4(0.58f, 0.46f, 1.f, 1.f);
  s.ScaleAllSizes(ui);
}

bool Editor::button(const char* label, bool enabled, bool accent) {
  if (!enabled) ImGui::BeginDisabled();
  if (accent) ImGui::PushStyleColor(ImGuiCol_Button, ImGui::ColorConvertU32ToFloat4(kAccent));
  const float h = 34.f * ImGui::GetIO().FontGlobalScale * (kFontBasePx / 13.f);
  const bool r = ImGui::Button(label, V(-FLT_MIN, h));
  if (accent) ImGui::PopStyleColor();
  buttons_.push_back({label, ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
  if (!enabled) ImGui::EndDisabled();
  return r && enabled;
}

// ------------------------------------------------------------------ state

void Editor::enterEditor() {
  screen_ = Screen::Editor;
  playhead_ = prevPlayhead_ = 0.f;
  playing_ = false;
  clearSel();
  zoom_ = 1.f;
  scroll_ = 0.f;
  showTimeline_ = true;  // you want to see the timeline while editing
  Preset* p = cur();
  if (p && !p->keys.empty()) {
    mode_ = CamMode::Follow;
    cam_ = p->keys.front();
  }
}

void Editor::follow() {
  Preset* p = cur();
  if (p && !p->keys.empty()) {
    mode_ = CamMode::Follow;
    cam_ = samplePreset(*p, playhead_);
  }
}

void Editor::addKey() {
  Preset* p = cur();
  if (!p) return toast("Create a preset first");
  const int k = p->addKey(cam_);
  const Layout L = computeLayout(*p);
  playhead_ = keyTime(L, k);
  clearSel();
  selKey_ = k;
  char m[64];
  std::snprintf(m, sizeof m, poseLinked_ ? "Key %d set" : "Key %d set (player pose not linked yet)", k + 1);
  toast(m);
}

void Editor::updateKey() {
  Preset* p = cur();
  if (!p || selKey_ < 0 || selKey_ >= (int)p->keys.size()) return toast("Select a key first");
  p->setKey(selKey_, cam_);
  toast("Key updated");
}

void Editor::deleteKey() {
  Preset* p = cur();
  if (!p || selKey_ < 0 || selKey_ >= (int)p->keys.size()) return toast("Select a key first");
  p->removeKey(selKey_);
  clearSel();
  playhead_ = std::min(playhead_, presetTotal(*p));
  toast("Key deleted");
}

void Editor::goLive() {
  playing_ = false;
  mode_ = CamMode::Free;
}

void Editor::tick(float dt, EditorHost& host) {
  if (toastT_ > 0.f) toastT_ -= dt;
  if (deleteArm_ > 0.f) deleteArm_ -= dt;

  Preset* p = cur();

  // LIVE: the player flies around with the game's own controls; we only read where they are.
  if (mode_ == CamMode::Free && host.readPose) {
    Pose pp;
    poseLinked_ = host.readPose(pp);
    if (poseLinked_) cam_ = pp;
  }

  prevPlayhead_ = playhead_;
  if (playing_ && p) {
    const float total = presetTotal(*p);
    playhead_ += dt;
    if (playhead_ >= total) {
      playhead_ = total;
      playing_ = false;
    }
    // Real fades are fired once, at the start of the dip (so the black peaks at the cut).
    const Layout L = computeLayout(*p);
    for (size_t i = 0; i + 1 < p->segs.size(); ++i) {
      const Transition& tr = p->segs[i].after;
      if (tr.type != TransType::Fade || tr.duration <= 0.f) continue;
      const float trig = L.seg[i].end - tr.duration * 0.5f;
      if (prevPlayhead_ < trig && playhead_ >= trig && host.onFade) host.onFade(tr.duration);
    }
    follow();
  }
  fadeA_ = (p && mode_ == CamMode::Follow) ? fadeAlpha(*p, playhead_) : 0.f;

  // The game camera is only taken over while playing / scrubbing; going back to LIVE returns it.
  if (mode_ == CamMode::Follow) {
    if (host.onCamera) host.onCamera(cam_, dt);
  } else if (prevMode_ == CamMode::Follow && host.onLive) {
    host.onLive();
  }
  prevMode_ = mode_;
}

// ------------------------------------------------------------------- draw

void Editor::draw(const ImVec2& disp, float dt, EditorHost& host) {
  ImGuiIO& io = ImGui::GetIO();
  const float ui = std::max(0.6f, disp.y / 540.f);
  if (std::fabs(ui - styleUi_) > 0.01f) {
    applyStyle(ui);
    styleUi_ = ui;
  }
  io.FontGlobalScale = ui * 13.f / kFontBasePx;

  host_ = &host;
  buttons_.clear();
  touch_.beginZones();
  tick(dt, host);

  // keep panel sizes sane for this screen
  const float wBase = disp.x / ui, hBase = disp.y / ui;
  leftW_ = std::clamp(leftW_, 150.f, std::max(150.f, wBase * 0.45f));
  rightW_ = std::clamp(rightW_, 170.f, std::max(170.f, wBase * 0.45f));
  tlH_ = std::clamp(tlH_, 150.f, std::max(150.f, hBase * 0.62f));

  const float gap = 22.f * ui;          // draggable divider
  const float topPad = 40.f * ui;       // room for the toggle buttons
  const float rightTop = 84.f * ui;     // below the launcher's floating button
  const float lw = showEditor_ ? leftW_ * ui : 0.f;
  const float rw = showTools_ ? rightW_ * ui : 0.f;
  const float th = showTimeline_ ? tlH_ * ui : 0.f;
  const float px0 = lw > 0.f ? lw + gap : 0.f;
  const float px1 = disp.x - (rw > 0.f ? rw + gap : 0.f);
  const float py1 = disp.y - (th > 0.f ? th + gap : 0.f);

  ImGui::GetBackgroundDrawList()->AddRectFilled(V(0, 0), disp, IM_COL32(4, 5, 10, 255));

  if (showEditor_) drawLeft(0.f, topPad, lw, py1 - topPad, ui, host);
  if (showTools_) drawTools(disp.x - rw, rightTop, rw, py1 - rightTop, ui, host);
  if (showTimeline_) drawBottom(0.f, py1 + gap, disp.x, th, ui);
  drawPreview(px0, 0.f, px1 - px0, py1, ui, host);

  if (showEditor_) splitter("##splitL", V(lw, topPad), V(gap, py1 - topPad), true, leftW_, 1.f, ui, 150.f, wBase * 0.45f);
  if (showTools_)
    splitter("##splitR", V(px1, rightTop), V(gap, py1 - rightTop), true, rightW_, -1.f, ui, 170.f, wBase * 0.45f);
  if (showTimeline_) splitter("##splitB", V(0, py1), V(disp.x, gap), false, tlH_, -1.f, ui, 150.f, hBase * 0.62f);

  drawToggles(disp, ui, py1, host);
  drawCreatePopup(disp, ui);
  drawTransitionPopup(disp, ui);
  popupOpen_ = createOpen_ || transOpen_;

  // Areas that swallow touches. Everything else (the game view) passes through to the game.
  if (showEditor_) touch_.setBlock(V(0, topPad), V(lw + gap, py1));
  if (showTools_) touch_.setBlock(V(px1, rightTop), V(disp.x, py1));
  if (showTimeline_) touch_.setBlock(V(0, py1), V(disp.x, disp.y));
  if (popupOpen_) touch_.setBlock(V(0, 0), disp);
}

void Editor::splitter(const char* id, ImVec2 pos, ImVec2 size, bool vertical, float& v, float dir, float ui,
                      float minB, float maxB) {
  ImGuiIO& io = ImGui::GetIO();
  ImGui::SetNextWindowPos(pos, ImGuiCond_Always);
  ImGui::SetNextWindowSize(size, ImGuiCond_Always);
  ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, V(0, 0));
  ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0.f);
  ImGui::Begin(id, nullptr, kPanel | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse);
  ImGui::InvisibleButton("##drag", size);
  buttons_.push_back({id, ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
  const bool active = ImGui::IsItemActive();
  if (active) {
    v += dir * (vertical ? io.MouseDelta.x : io.MouseDelta.y) / ui;
    v = std::clamp(v, minB, std::max(minB, maxB));
  }
  ImDrawList* dl = ImGui::GetWindowDrawList();
  const ImU32 col = active ? kAccent : IM_COL32(70, 78, 130, 255);
  const ImVec2 c = V(pos.x + size.x * 0.5f, pos.y + size.y * 0.5f);
  if (vertical) {
    dl->AddLine(V(c.x, pos.y), V(c.x, pos.y + size.y), col, 2.f);
    for (int i = -1; i <= 1; ++i) dl->AddCircleFilled(V(c.x, c.y + i * 10.f * ui), 2.5f * ui, kText);
  } else {
    dl->AddLine(V(pos.x, c.y), V(pos.x + size.x, c.y), col, 2.f);
    for (int i = -1; i <= 1; ++i) dl->AddCircleFilled(V(c.x + i * 10.f * ui, c.y), 2.5f * ui, kText);
  }
  ImGui::End();
  ImGui::PopStyleVar(2);
}

// Touch-drag scrolling for panels (ImGui only scrolls with a mouse wheel by default).
void Editor::touchScroll(float ui) {
  ImGuiIO& io = ImGui::GetIO();
  if (ImGui::IsWindowHovered() && ImGui::IsMouseDragging(0, 8.f * ui) && !ImGui::IsAnyItemActive())
    ImGui::SetScrollY(ImGui::GetScrollY() - io.MouseDelta.y);
}

bool Editor::toggleBtn(const char* id, const char* label, ImVec2 pos, float width, bool on) {
  const float h = 34.f * styleUi_;
  ImGui::SetNextWindowPos(pos, ImGuiCond_Always);
  ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, V(0, 0));
  ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0.f);
  ImGui::Begin(id, nullptr,
               ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoMove |
                   ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_AlwaysAutoResize);
  if (on) ImGui::PushStyleColor(ImGuiCol_Button, ImGui::ColorConvertU32ToFloat4(kAccent));
  else ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.05f, 0.06f, 0.12f, 0.88f));
  const bool r = ImGui::Button(label, V(width, h));
  ImGui::PopStyleColor();
  const ImVec2 mn = ImGui::GetItemRectMin(), mx = ImGui::GetItemRectMax();
  buttons_.push_back({label, mn, mx});
  touch_.setBlock(mn, mx);  // touches here belong to ImGui, not to the preview's look zone
  ImGui::End();
  ImGui::PopStyleVar(2);
  return r;
}

void Editor::drawToggles(const ImVec2& disp, float ui, float /*py1*/, EditorHost& host) {
  if (toggleBtn("##tEditor", "Editor", V(8.f * ui, 6.f * ui), 96.f * ui, showEditor_)) showEditor_ = !showEditor_;
  if (toggleBtn("##tClose", "X", V(8.f * ui + 104.f * ui, 6.f * ui), 44.f * ui, false)) {
    if (host.onSave) host.onSave();
    if (host.onClose) host.onClose();
  }
  if (toggleBtn("##tTools", "Tools", V(disp.x - kRightInset * ui - 96.f * ui, 6.f * ui), 96.f * ui, showTools_))
    showTools_ = !showTools_;
  if (!showTimeline_ &&
      toggleBtn("##tTimeline", "Timeline", V(disp.x * 0.80f - 56.f * ui, disp.y - 44.f * ui), 112.f * ui, false))
    showTimeline_ = true;
}

// --------------------------------------------------------------- left panel

void Editor::drawLeft(float x, float y, float w, float h, float ui, EditorHost& host) {
  ImGui::SetNextWindowPos(V(x, y), ImGuiCond_Always);
  ImGui::SetNextWindowSize(V(w, h), ImGuiCond_Always);
  ImGui::Begin("##left", nullptr, kPanel);
  touchScroll(ui);

  // two half-width buttons on one row keep the panel short enough to fit above the timeline
  auto half = [&](const char* label, bool enabled = true) {
    const float bw = (ImGui::GetContentRegionAvail().x - ImGui::GetStyle().ItemSpacing.x) * 0.5f;
    const float bh = 34.f * ui;
    if (!enabled) ImGui::BeginDisabled();
    const bool r = ImGui::Button(label, V(bw, bh));
    buttons_.push_back({label, ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    if (!enabled) ImGui::EndDisabled();
    return r && enabled;
  };

  if (screen_ == Screen::Main) {
    if (button("Presets", true, true)) screen_ = Screen::Presets;
  } else if (screen_ == Screen::Presets) {
    ImGui::TextColored(ImGui::ColorConvertU32ToFloat4(kAccent), "Presets  v");
    for (int i = 0; i < kSlotCount; ++i) {
      ImGui::PushID(i);
      const bool sel = (i == selSlot_);
      char label[32];
      std::snprintf(label, sizeof label, "%s Slot %d", sel ? "v" : ">", i + 1);
      if (ImGui::Selectable(label, sel, 0, V(0, 32.f * ui))) selSlot_ = i;
      char rec[16];
      std::snprintf(rec, sizeof rec, "Slot %d", i + 1);
      buttons_.push_back({rec, ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
      if (lib_.slots[i]) {
        ImGui::TextDisabled("  %s  [%s]", lib_.slots[i]->name.c_str(), lib_.slots[i]->category.c_str());
        if (sel) {
          ImGui::Indent(14.f * ui);
          if (button("Camera 1", true, true)) enterEditor();
          ImGui::Unindent(14.f * ui);
        }
      } else {
        ImGui::TextDisabled("  (empty)");
      }
      ImGui::PopID();
    }
    ImGui::Spacing();
    if (button("+ Create New")) {
      createPending_ = true;
      createOpen_ = true;
      nameBuf_[0] = 0;
      catIdx_ = 0;
    }
    const bool occupied = lib_.slots[selSlot_].has_value();
    if (half(deleteArm_ > 0.f ? "Tap again" : "- Delete Slot", occupied)) {
      if (deleteArm_ > 0.f) {
        lib_.slots[selSlot_].reset();
        deleteArm_ = 0.f;
        if (host.onSave) host.onSave();
        toast("Slot deleted");
      } else {
        deleteArm_ = 3.f;
      }
    }
    ImGui::SameLine();
    if (half("< Back")) screen_ = Screen::Main;
  } else {  // Editor
    ImGui::TextColored(ImGui::ColorConvertU32ToFloat4(kAccent), "EDITOR");
    if (Preset* p = cur()) {
      ImGui::TextDisabled("%s", p->name.c_str());
      ImGui::TextDisabled("Keys %d   Segments %d", (int)p->keys.size(), p->segmentCount());
    }
    button("Camera 1", true, true);
    if (half("+ Add Key")) addKey();
    ImGui::SameLine();
    if (half("Live")) {
      goLive();
      toast("Live: the game camera is yours again");
    }
    if (half("Update Key", selKey_ >= 0)) updateKey();
    ImGui::SameLine();
    if (half("- Delete Key")) deleteKey();
    if (half("< Back")) {
      if (host.onSave) host.onSave();
      playing_ = false;
      screen_ = Screen::Presets;
    }
    ImGui::SameLine();
    if (half("Save")) {
      if (host.onSave) host.onSave();
      toast("Saved");
    }
  }
  ImGui::End();
}

void Editor::drawCreatePopup(const ImVec2& disp, float ui) {
  if (createPending_) {
    ImGui::OpenPopup("Create Preset");
    createPending_ = false;
  }
  ImGui::SetNextWindowPos(V(disp.x * 0.5f, disp.y * 0.42f), ImGuiCond_Always, V(0.5f, 0.5f));
  ImGui::SetNextWindowSize(V(std::min(disp.x * 0.6f, 520.f * ui), 0.f), ImGuiCond_Always);
  if (ImGui::BeginPopupModal("Create Preset", nullptr,
                             ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings)) {
    ImGui::Text("Name:");
    ImGui::SetNextItemWidth(-FLT_MIN);
    ImGui::InputText("##name", nameBuf_, sizeof nameBuf_);
    buttons_.push_back({"input:name", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    ImGui::Text("Category type:");
    ImGui::SetNextItemWidth(-FLT_MIN);
    ImGui::Combo("##cat", &catIdx_, kCategories, kCategoryCount);
    ImGui::Spacing();
    const float half = (ImGui::GetContentRegionAvail().x - ImGui::GetStyle().ItemSpacing.x) * 0.5f;
    const float bh = 34.f * ui;
    if (ImGui::Button("Back", V(half, bh))) {
      createOpen_ = false;
      ImGui::CloseCurrentPopup();
    }
    buttons_.push_back({"Back", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    ImGui::SameLine();
    const bool ok = nameBuf_[0] != 0;
    if (!ok) ImGui::BeginDisabled();
    const bool create = ImGui::Button("Create", V(half, bh));
    buttons_.push_back({"Create", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    if (!ok) ImGui::EndDisabled();
    if (create && ok) {
      int target = -1;
      if (!lib_.slots[selSlot_]) target = selSlot_;
      else
        for (int i = 0; i < kSlotCount; ++i)
          if (!lib_.slots[i]) {
            target = i;
            break;
          }
      if (target < 0) {
        toast("All slots are full - delete one first");
      } else {
        Preset p;
        p.name = nameBuf_;
        p.category = kCategories[std::clamp(catIdx_, 0, kCategoryCount - 1)];
        lib_.slots[target] = p;
        selSlot_ = target;
        if (host_ && host_->onSave) host_->onSave();
        createOpen_ = false;
        ImGui::CloseCurrentPopup();
        enterEditor();
      }
    }
    ImGui::EndPopup();
  } else if (createOpen_ && !createPending_) {
    createOpen_ = false;
  }
}

// ---------------------------------------------------------------- preview

bool Editor::zoneBtn(ZoneId z, ImVec2 c, float r, Icon ic, bool on) {
  ImDrawList* dl = ImGui::GetWindowDrawList();
  if (!popupOpen_) touch_.setZone(z, V(c.x - r, c.y - r), V(c.x + r, c.y + r));
  const bool pressed = touch_.held(z);
  const ImU32 bg = (on || pressed) ? kAccent : kDisc;
  dl->AddCircleFilled(c, r, bg, 28);
  dl->AddCircle(c, r, IM_COL32(255, 255, 255, 60), 28, 1.5f);
  switch (ic) {
    case Icon::Moon:
      dl->AddCircleFilled(c, 0.42f * r, kText, 20);
      dl->AddCircleFilled(V(c.x + 0.22f * r, c.y - 0.12f * r), 0.38f * r, bg, 20);
      break;
    case Icon::Sun:
      dl->AddCircleFilled(c, 0.24f * r, kText, 16);
      for (int i = 0; i < 8; ++i) {
        const float a = i * kPi / 4.f;
        dl->AddLine(V(c.x + std::cos(a) * 0.40f * r, c.y + std::sin(a) * 0.40f * r),
                    V(c.x + std::cos(a) * 0.62f * r, c.y + std::sin(a) * 0.62f * r), kText, 2.f);
      }
      break;
  }
  return !popupOpen_ && touch_.takeClick(z);
}

void Editor::drawPreview(float x, float y, float w, float h, float ui, EditorHost& host) {
  w = std::max(w, 60.f * ui);
  h = std::max(h, 60.f * ui);
  ImGui::SetNextWindowPos(V(x, y), ImGuiCond_Always);
  ImGui::SetNextWindowSize(V(w, h), ImGuiCond_Always);
  ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, V(0, 0));
  ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0.f);
  ImGui::Begin("##preview", nullptr,
               kPanel | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoScrollbar);
  ImGui::PopStyleVar(2);
  ImDrawList* dl = ImGui::GetWindowDrawList();
  const ImVec2 p0 = ImGui::GetWindowPos();
  const ImVec2 p1 = V(p0.x + w, p0.y + h);
  pvMin_ = p0;
  pvMax_ = p1;
  dl->AddRectFilled(p0, p1, IM_COL32(6, 8, 14, 255));

  // --- the game frame, aspect-fitted inside the preview ---
  if (host.gameTex && host.gameW > 0 && host.gameH > 0) {
    const float ar = (float)host.gameW / (float)host.gameH;
    float iw = w, ih = w / ar;
    if (ih > h) {
      ih = h;
      iw = h * ar;
    }
    const ImVec2 i0 = V(p0.x + (w - iw) * 0.5f, p0.y + (h - ih) * 0.5f);
    const ImVec2 i1 = V(i0.x + iw, i0.y + ih);
    const ImVec2 uv0 = V(0.f, 1.f), uv1 = V(1.f, 0.f);  // GL framebuffer is bottom-up
    dl->AddImage(host.gameTex, i0, i1, uv0, uv1);
    if ((fullBright_ || nightVision_) && host.additiveCb) {
      dl->AddCallback(host.additiveCb, nullptr);
      if (fullBright_) {
        dl->AddImage(host.gameTex, i0, i1, uv0, uv1, IM_COL32(255, 255, 255, 255));
        dl->AddRectFilled(i0, i1, IM_COL32(38, 38, 38, 255));
      }
      if (nightVision_) {
        dl->AddImage(host.gameTex, i0, i1, uv0, uv1, IM_COL32(0, 255, 70, 255));
        dl->AddImage(host.gameTex, i0, i1, uv0, uv1, IM_COL32(0, 190, 60, 255));
        dl->AddRectFilled(i0, i1, IM_COL32(0, 26, 6, 255));
      }
      dl->AddCallback(ImDrawCallback_ResetRenderState, nullptr);
    }
  } else {
    const char* t = "(waiting for game frame)";
    const ImVec2 ts = ImGui::CalcTextSize(t);
    dl->AddText(V(p0.x + (w - ts.x) * 0.5f, p0.y + (h - ts.y) * 0.5f), kDim, t);
  }
  if (fadeA_ > 0.001f) dl->AddRectFilled(p0, p1, IM_COL32(0, 0, 0, (int)(255.f * std::min(1.f, fadeA_))));

  // --- small status chip (no coordinates: a creator does not need them) ---
  {
    const char* chip = mode_ == CamMode::Free ? "WORLD PREVIEW  Live" : "WORLD PREVIEW  Playback";
    const ImVec2 ts = ImGui::CalcTextSize(chip);
    const ImVec2 c0 = V(p0.x + (showEditor_ ? 8.f : 120.f) * ui, p0.y + 8.f * ui);
    dl->AddRectFilled(c0, V(c0.x + ts.x + 14.f * ui, c0.y + ts.y + 8.f * ui), kDisc, 6.f * ui);
    dl->AddText(V(c0.x + 7.f * ui, c0.y + 4.f * ui), kText, chip);
  }

  // --- night vision (moon) + full brightness (sun), bottom centre ---
  const float cx = p0.x + w * 0.5f;
  if (zoneBtn(ZNight, V(cx - 36.f * ui, p1.y - 34.f * ui), 24.f * ui, Icon::Moon, nightVision_)) nightVision_ = !nightVision_;
  if (zoneBtn(ZBright, V(cx + 36.f * ui, p1.y - 34.f * ui), 24.f * ui, Icon::Sun, fullBright_)) fullBright_ = !fullBright_;

  // --- toast ---
  if (toastT_ > 0.f && !toast_.empty()) {
    const ImVec2 ts = ImGui::CalcTextSize(toast_.c_str());
    const float a = std::min(1.f, toastT_ * 2.f);
    const ImVec2 t0 = V(cx - ts.x * 0.5f - 10.f * ui, p0.y + h * 0.5f - ts.y * 0.5f - 6.f * ui);
    dl->AddRectFilled(t0, V(t0.x + ts.x + 20.f * ui, t0.y + ts.y + 12.f * ui), IM_COL32(18, 22, 38, (int)(220 * a)), 8.f * ui);
    dl->AddText(V(t0.x + 10.f * ui, t0.y + 6.f * ui), IM_COL32(235, 235, 250, (int)(255 * a)), toast_.c_str());
  }
  ImGui::End();
}

// ------------------------------------------------------------------ tools

void Editor::drawTools(float x, float y, float w, float h, float ui, EditorHost& host) {
  ImGui::SetNextWindowPos(V(x, y), ImGuiCond_Always);
  ImGui::SetNextWindowSize(V(w, h), ImGuiCond_Always);
  ImGui::Begin("##tools", nullptr, kPanel);
  touchScroll(ui);
  const ImVec4 accent = ImGui::ColorConvertU32ToFloat4(kAccent);
  ImGui::TextColored(accent, "TOOLS");
  ImGui::Separator();

  Preset* p = cur();
  // ---------------- inspector: whatever is selected on the timeline ----------------
  if (p && selSeg_ >= 0 && selSeg_ < p->segmentCount()) {
    Segment& s = p->segs[selSeg_];
    ImGui::TextColored(accent, "SEGMENT %d  (key %d -> %d)", selSeg_ + 1, selSeg_ + 1, selSeg_ + 2);
    ImGui::Text("Duration");
    ImGui::SetNextItemWidth(-FLT_MIN);
    ImGui::SliderFloat("##sdur", &s.duration, 0.5f, 60.f, "%.1f s");
    ImGui::Text("Ease type");
    ImGui::SetNextItemWidth(-FLT_MIN);
    ImGui::Combo("##sease", &s.ease, kEaseNames, kEaseCount);
    buttons_.push_back({"seg:ease", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    if (button("Delete Segment")) {
      p->removeSegment(selSeg_);
      clearSel();
      playhead_ = std::min(playhead_, presetTotal(*p));
      toast("Segment deleted");
    }
  } else if (p && selKey_ >= 0 && selKey_ < (int)p->keys.size()) {
    ImGui::TextColored(accent, "KEY %d", selKey_ + 1);
    ImGui::TextDisabled("Move the camera, then:");
    if (button("Update from camera")) updateKey();
    if (button("Delete this key")) deleteKey();
  } else if (p && selTrans_ >= 0 && selTrans_ + 1 < p->segmentCount()) {
    ImGui::TextColored(accent, "TRANSITION  %d -> %d", selTrans_ + 1, selTrans_ + 2);
    ImGui::TextDisabled("Tap the + on the timeline to edit.");
  } else if (p && selPin_ >= 0 && selPin_ < (int)p->pins.size()) {
    Pin& pin = p->pins[selPin_];
    ImGui::TextColored(accent, "KEY POINT");
    char buf[48];
    std::snprintf(buf, sizeof buf, "%s", pin.label.c_str());
    ImGui::SetNextItemWidth(-FLT_MIN);
    if (ImGui::InputText("##pinlabel", buf, sizeof buf)) pin.label = buf;
    buttons_.push_back({"input:pin", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    ImGui::Text("Time");
    ImGui::SetNextItemWidth(-FLT_MIN);
    ImGui::SliderFloat("##pint", &pin.t, 0.f, std::max(10.f, presetTotal(*p) + 4.f), "%.2f s");
    if (button("Delete Key Point")) {
      p->removePin(selPin_);
      clearSel();
    }
  } else {
    ImGui::TextDisabled("Tap a segment, key or key point on the timeline to edit it.");
  }

  ImGui::Separator();
  ImGui::TextDisabled("MOVE / LOOK");
  ImGui::TextWrapped("Use the game's own keyboard & mouse controls (turn on Pojav Controls in the launcher). Touches outside these panels go straight to the game.");
  float fov = cam_.fov;
  ImGui::Text("FOV for new keys");
  ImGui::SetNextItemWidth(-FLT_MIN);
  if (ImGui::SliderFloat("##fov", &fov, 30.f, 110.f, "%.0f")) cam_.fov = fov;

  ImGui::Separator();
  ImGui::TextDisabled("GAME LINK");
  if (host.linkConnected) {
    ImGui::TextColored(ImVec4(0.35f, 0.85f, 0.45f, 1.f), "Connected");
    ImGui::TextDisabled("%d sent, %d failed", host.cmdSent, host.cmdFailed);
  } else {
    ImGui::TextColored(ImVec4(1.f, 0.62f, 0.25f, 1.f), "NOT connected");
    ImGui::TextWrapped("Editor only. The game view follows this camera once the command hook is added.");
  }
  if (poseLinked_) ImGui::TextColored(ImVec4(0.35f, 0.85f, 0.45f, 1.f), "Player pose: connected");
  else ImGui::TextColored(ImVec4(1.f, 0.62f, 0.25f, 1.f), "Player pose: NOT connected");
  bool rel = host.linkRelative;
  if (ImGui::Checkbox("Relative positions", &rel) && host.onSetRelative) host.onSetRelative(rel);
  ImGui::End();
}

// ----------------------------------------------------- playback + timeline

void Editor::drawBottom(float x, float y, float w, float h, float ui) {
  ImGui::SetNextWindowPos(V(x, y), ImGuiCond_Always);
  ImGui::SetNextWindowSize(V(w, h), ImGuiCond_Always);
  ImGui::Begin("##bottom", nullptr, kPanel | ImGuiWindowFlags_NoScrollbar);
  Preset* p = cur();
  const float total = p ? presetTotal(*p) : 0.f;
  const Layout L = p ? computeLayout(*p) : Layout{};
  const float bh = 32.f * ui, bw = 62.f * ui;

  auto small = [&](const char* label, float width) {
    const bool r = ImGui::Button(label, V(width, bh));
    buttons_.push_back({label, ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    return r;
  };

  if (small("<<", bw)) {
    float t = 0.f;
    if (p)
      for (int k = 0; k < (int)p->keys.size(); ++k)
        if (keyTime(L, k) < playhead_ - 0.05f) t = keyTime(L, k);
    playhead_ = t;
    playing_ = false;
    follow();
  }
  ImGui::SameLine();
  if (small("Play", bw + 10.f * ui)) {
    if (p && p->segmentCount() > 0) {
      if (playhead_ >= total - 0.01f) playhead_ = 0.f;
      playing_ = true;
      follow();
    } else {
      toast("Add at least 2 keys first");
    }
  }
  ImGui::SameLine();
  if (small("Pause", bw + 10.f * ui)) playing_ = false;
  ImGui::SameLine();
  if (small(">>", bw)) {
    float t = total;
    if (p)
      for (int k = 0; k < (int)p->keys.size(); ++k)
        if (keyTime(L, k) > playhead_ + 0.05f) {
          t = keyTime(L, k);
          break;
        }
    playhead_ = t;
    playing_ = false;
    follow();
  }
  ImGui::SameLine();
  char a[24], b[24], tb[64];
  fmtTime(playhead_, a, sizeof a);
  fmtTime(total, b, sizeof b);
  std::snprintf(tb, sizeof tb, "%s / %s", a, b);
  ImGui::AlignTextToFramePadding();
  ImGui::TextUnformatted(tb);
  ImGui::SameLine();
  if (small("+ Pin", 70.f * ui)) {
    if (p) {
      const int j = p->addPin(playhead_, "Note");
      clearSel();
      selPin_ = j;
      showTools_ = true;
      toast("Key point added");
    } else {
      toast("Open a preset first");
    }
  }

  // right side: zoom + hide
  const float clusterW = 330.f * ui;
  ImGui::SameLine(std::max(ImGui::GetCursorPosX(), w - clusterW - ImGui::GetStyle().WindowPadding.x));
  if (small("-", 36.f * ui)) zoom_ = std::max(1.f, zoom_ - 0.5f);
  ImGui::SameLine();
  ImGui::SetNextItemWidth(130.f * ui);
  ImGui::SliderFloat("##zoom", &zoom_, 1.f, 8.f, "");
  ImGui::SameLine();
  if (small("+", 36.f * ui)) zoom_ = std::min(8.f, zoom_ + 0.5f);
  ImGui::SameLine();
  if (small("Hide", 70.f * ui)) showTimeline_ = false;

  drawTimeline(ui);
  ImGui::End();
}

void Editor::drawTimeline(float ui) {
  ImGuiIO& io = ImGui::GetIO();
  Preset* p = cur();
  const Layout L = p ? computeLayout(*p) : Layout{};
  const float viewDur = std::max(10.f, L.total + 4.f);
  ImDrawList* dl = ImGui::GetWindowDrawList();

  const ImVec2 origin = ImGui::GetCursorScreenPos();
  const float availW = ImGui::GetContentRegionAvail().x;
  const float gutter = 92.f * ui;
  const float x0 = origin.x + gutter, x1 = origin.x + availW - 4.f * ui;
  const float areaW = std::max(10.f, x1 - x0);
  const float visSec = viewDur / std::max(1.f, zoom_);
  const float pps = areaW / visSec;
  scroll_ = std::clamp(scroll_, 0.f, std::max(0.f, viewDur - visSec));
  auto xOf = [&](float t) { return x0 + (t - scroll_) * pps; };
  auto tOf = [&](float x) { return std::clamp((x - x0) / pps + scroll_, 0.f, viewDur); };

  const float pinH = 26.f * ui, rulerH = 24.f * ui, stripH = 38.f * ui, laneH = 62.f * ui;
  const ImVec2 pMin = V(x0, origin.y), pMax = V(x1, origin.y + pinH);
  const ImVec2 rMin = V(x0, pMax.y + 2.f * ui), rMax = V(x1, rMin.y + rulerH);
  const ImVec2 lMin = V(x0, rMax.y + 4.f * ui), lMax = V(x1, lMin.y + laneH);
  const float stripTop = lMin.y + 2.f * ui, stripBot = stripTop + stripH, midY = (stripTop + stripBot) * 0.5f;

  dl->AddText(V(origin.x + 4.f * ui, pMin.y + 5.f * ui), IM_COL32(255, 170, 60, 255), "KEY POINTS");
  dl->AddText(V(origin.x + 4.f * ui, rMin.y + 4.f * ui), IM_COL32(120, 110, 250, 255), "TIMELINE");
  dl->AddText(V(origin.x + 4.f * ui, midY - 8.f * ui), IM_COL32(120, 150, 255, 255), "Camera");
  dl->AddRectFilled(pMin, pMax, IM_COL32(16, 14, 22, 255), 4.f * ui);
  dl->AddRectFilled(rMin, rMax, IM_COL32(14, 17, 30, 255), 4.f * ui);
  dl->AddRectFilled(lMin, lMax, IM_COL32(10, 13, 24, 255), 4.f * ui);

  dl->PushClipRect(V(x0 - 1.f, origin.y), V(x1 + 1.f, lMax.y), true);
  // ruler ticks
  static const float steps[] = {0.25f, 0.5f, 1.f, 2.f, 5.f, 10.f, 15.f, 30.f, 60.f};
  float step = 60.f;
  for (float s : steps)
    if (s * pps >= 70.f * ui) {
      step = s;
      break;
    }
  for (float t = std::ceil(scroll_ / step) * step; t <= scroll_ + visSec + 1e-3f; t += step) {
    const float x = xOf(t);
    dl->AddLine(V(x, rMax.y - 8.f * ui), V(x, lMax.y), IM_COL32(60, 66, 100, 130), 1.f);
    char lb[16];
    if (step < 1.f) std::snprintf(lb, sizeof lb, "%.2fs", t);
    else std::snprintf(lb, sizeof lb, "%02ds", (int)(t + 0.5f));
    dl->AddText(V(x + 3.f * ui, rMin.y + 4.f * ui), kDim, lb);
  }

  // ---- segment strips (white / green / blue) + hold gaps ----
  if (p) {
    for (int i = 0; i < p->segmentCount(); ++i) {
      const float sx0 = xOf(L.seg[i].start), sx1 = xOf(L.seg[i].end);
      const ImU32 fill = kStrip[i % 3];
      dl->AddRectFilled(V(sx0 + 1.f, stripTop), V(sx1 - 1.f, stripBot), fill, 6.f * ui);
      if (i == selSeg_) dl->AddRect(V(sx0 + 1.f, stripTop), V(sx1 - 1.f, stripBot), IM_COL32(255, 214, 90, 255), 6.f * ui, 0, 2.5f);
      char lb[48];
      std::snprintf(lb, sizeof lb, "Segment %d   %.1fs", i + 1, p->segs[i].duration);
      dl->PushClipRect(V(sx0 + 14.f * ui, stripTop), V(sx1 - 14.f * ui, stripBot), true);
      dl->AddText(V(sx0 + 16.f * ui, midY - 8.f * ui), kStripText[i % 3], lb);
      dl->PopClipRect();
      if (L.seg[i].holdEnd > L.seg[i].end + 1e-4f) {
        const float hx1 = xOf(L.seg[i].holdEnd);
        dl->AddRectFilled(V(sx1, stripTop), V(hx1, stripBot), IM_COL32(48, 54, 92, 255), 4.f * ui);
        dl->AddText(V(sx1 + 4.f * ui, midY - 8.f * ui), kDim, "Hold");
      }
    }
  } else {
    dl->AddText(V(x0 + 10.f * ui, midY - 8.f * ui), kDim, "Open a preset (Editor > Presets) to see its segments here");
  }
  dl->PopClipRect();

  // ---- interaction. Submission order = priority: ruler/lane < strips < keys/transitions/pins ----
  ImGui::SetCursorScreenPos(rMin);
  ImGui::InvisibleButton("##ruler", V(areaW, rulerH));
  buttons_.push_back({"timeline:ruler", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
  if (ImGui::IsItemActive()) {
    playhead_ = std::min(tOf(io.MousePos.x), std::max(L.total, 0.f));
    playing_ = false;
    follow();
  }
  ImGui::SetCursorScreenPos(lMin);
  ImGui::InvisibleButton("##lane", V(areaW, laneH));
  buttons_.push_back({"timeline:lane", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
  ImGui::SetItemAllowOverlap();
  if (ImGui::IsItemActive()) {
    playhead_ = std::min(tOf(io.MousePos.x), std::max(L.total, 0.f));
    playing_ = false;
    follow();
  }

  if (p) {
    for (int i = 0; i < p->segmentCount(); ++i) {
      const float bx0 = std::max(xOf(L.seg[i].start), x0), bx1 = std::min(xOf(L.seg[i].end), x1);
      if (bx1 - bx0 < 2.f) continue;
      ImGui::SetCursorScreenPos(V(bx0, stripTop));
      char id[16];
      std::snprintf(id, sizeof id, "##seg%d", i);
      ImGui::InvisibleButton(id, V(bx1 - bx0, stripH));
      buttons_.push_back({"timeline:seg" + std::to_string(i), ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
      ImGui::SetItemAllowOverlap();
      if (ImGui::IsItemActivated()) {
        clearSel();
        selSeg_ = i;
        showTools_ = true;
      }
    }
    // key diamonds (also the drag handle that changes the segment length)
    for (int k = 0; k < (int)p->keys.size(); ++k) {
      const float tk = keyTime(L, k), x = xOf(tk);
      if (x < x0 - 14.f * ui || x > x1 + 14.f * ui) continue;
      ImGui::SetCursorScreenPos(V(x - 14.f * ui, midY - 14.f * ui));
      char id[16];
      std::snprintf(id, sizeof id, "##k%d", k);
      ImGui::InvisibleButton(id, V(28.f * ui, 28.f * ui));
      buttons_.push_back({"timeline:key" + std::to_string(k), ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
      if (ImGui::IsItemActivated()) {
        clearSel();
        selKey_ = k;
        playhead_ = tk;
        cam_ = p->keys[k];
        mode_ = CamMode::Follow;
        playing_ = false;
      }
      if (k >= 1 && ImGui::IsItemActive() && ImGui::IsMouseDragging(0, 4.f * ui)) {
        const float newEnd = tOf(io.MousePos.x);
        p->segs[k - 1].duration = std::clamp(newEnd - L.seg[k - 1].start, 0.1f, 3600.f);
        playhead_ = L.seg[k - 1].start + p->segs[k - 1].duration;
      }
    }
    // "+" between two segments -> transition popup
    for (int i = 0; i + 1 < p->segmentCount(); ++i) {
      const float xc = xOf((L.seg[i].end + L.seg[i].holdEnd) * 0.5f), yc = stripBot + 12.f * ui;
      if (xc < x0 - 12.f * ui || xc > x1 + 12.f * ui) continue;
      ImGui::SetCursorScreenPos(V(xc - 12.f * ui, yc - 12.f * ui));
      char id[16];
      std::snprintf(id, sizeof id, "##t%d", i);
      ImGui::InvisibleButton(id, V(24.f * ui, 24.f * ui));
      buttons_.push_back({"timeline:trans" + std::to_string(i), ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
      if (ImGui::IsItemActivated()) {
        clearSel();
        selTrans_ = i;
        transSeg_ = i;
        transPending_ = true;
        transOpen_ = true;
        transAnchor_ = V(xc, yc - 14.f * ui);
      }
    }
    // key points (pins)
    for (int j = 0; j < (int)p->pins.size(); ++j) {
      Pin& pin = p->pins[j];
      const float x = xOf(pin.t);
      if (x < x0 - 12.f * ui || x > x1 + 12.f * ui) continue;
      ImGui::SetCursorScreenPos(V(x - 12.f * ui, pMin.y));
      char id[16];
      std::snprintf(id, sizeof id, "##pin%d", j);
      ImGui::InvisibleButton(id, V(24.f * ui, pinH));
      buttons_.push_back({"timeline:pin" + std::to_string(j), ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
      if (ImGui::IsItemActivated()) {
        clearSel();
        selPin_ = j;
        showTools_ = true;
      }
      if (ImGui::IsItemActive() && ImGui::IsMouseDragging(0, 4.f * ui)) pin.t = tOf(io.MousePos.x);
    }
  }

  // ---- overlays drawn on top: diamonds, transition icons, pins, playhead ----
  dl->PushClipRect(V(x0 - 14.f * ui, origin.y), V(x1 + 14.f * ui, lMax.y + 14.f * ui), true);
  if (p) {
    for (int k = 0; k < (int)p->keys.size(); ++k) {
      const float x = xOf(keyTime(L, k)), r = 10.f * ui;
      const bool sel = (k == selKey_);
      const ImU32 col = sel ? IM_COL32(255, 214, 90, 255) : IM_COL32(255, 255, 255, 255);
      dl->AddQuadFilled(V(x, midY - r), V(x + r, midY), V(x, midY + r), V(x - r, midY), col);
      dl->AddQuad(V(x, midY - r), V(x + r, midY), V(x, midY + r), V(x - r, midY), IM_COL32(0, 0, 0, 170), 1.5f);
    }
    for (int i = 0; i + 1 < p->segmentCount(); ++i) {
      const Transition& tr = p->segs[i].after;
      const float xc = xOf((L.seg[i].end + L.seg[i].holdEnd) * 0.5f), yc = stripBot + 12.f * ui, r = 10.f * ui;
      const char* g = tr.type == TransType::Instant ? "+" : (tr.type == TransType::Hold ? "H" : "F");
      const ImU32 fill = tr.type == TransType::Instant ? IM_COL32(240, 240, 250, 255)
                         : tr.type == TransType::Hold  ? kAccent
                                                       : IM_COL32(30, 30, 44, 255);
      dl->AddCircleFilled(V(xc, yc), r, fill, 20);
      if (i == selTrans_) dl->AddCircle(V(xc, yc), r + 2.f, IM_COL32(255, 214, 90, 255), 20, 2.f);
      else if (tr.type == TransType::Fade) dl->AddCircle(V(xc, yc), r, IM_COL32(255, 255, 255, 200), 20, 1.5f);
      const ImVec2 ts = ImGui::CalcTextSize(g);
      dl->AddText(V(xc - ts.x * 0.5f, yc - ts.y * 0.5f), tr.type == TransType::Instant ? IM_COL32(20, 22, 34, 255) : kText, g);
    }
    dl->PushClipRect(pMin, pMax, true);
    for (int j = 0; j < (int)p->pins.size(); ++j) {
      const Pin& pin = p->pins[j];
      const float x = xOf(pin.t);
      const bool sel = (j == selPin_);
      const ImU32 col = sel ? IM_COL32(255, 214, 90, 255) : IM_COL32(255, 160, 50, 255);
      dl->AddCircleFilled(V(x, pMin.y + 9.f * ui), 6.f * ui, col, 16);
      dl->AddTriangleFilled(V(x - 4.5f * ui, pMin.y + 12.f * ui), V(x + 4.5f * ui, pMin.y + 12.f * ui), V(x, pMin.y + 23.f * ui), col);
      dl->AddText(V(x + 9.f * ui, pMin.y + 4.f * ui), kText, pin.label.c_str());
    }
    dl->PopClipRect();
  }
  {
    const float x = xOf(playhead_);
    if (x >= x0 - 1.f && x <= x1 + 1.f) {
      dl->AddLine(V(x, rMin.y), V(x, lMax.y), IM_COL32(255, 255, 255, 230), 2.f);
      dl->AddTriangleFilled(V(x - 6.f * ui, rMin.y), V(x + 6.f * ui, rMin.y), V(x, rMin.y + 9.f * ui), IM_COL32(255, 255, 255, 240));
    }
  }
  dl->PopClipRect();

  // pan slider (only when zoomed in)
  if (zoom_ > 1.001f) {
    ImGui::SetCursorScreenPos(V(x0, lMax.y + 4.f * ui));
    ImGui::SetNextItemWidth(areaW);
    ImGui::SliderFloat("##pan", &scroll_, 0.f, std::max(0.01f, viewDur - visSec), "");
  }
}

// ------------------------------------------------------ transition popup

void Editor::drawTransitionPopup(const ImVec2& disp, float ui) {
  Preset* p = cur();
  if (transPending_) {
    ImGui::OpenPopup("##trans");
    transPending_ = false;
    transOpen_ = true;
  }
  ImGui::SetNextWindowPos(V(std::clamp(transAnchor_.x, 170.f * ui, disp.x - 170.f * ui), transAnchor_.y), ImGuiCond_Appearing,
                          V(0.5f, 1.f));
  ImGui::SetNextWindowSize(V(320.f * ui, 0.f), ImGuiCond_Appearing);
  if (ImGui::BeginPopup("##trans", ImGuiWindowFlags_NoSavedSettings)) {
    if (!p || transSeg_ < 0 || transSeg_ + 1 >= p->segmentCount()) {
      ImGui::CloseCurrentPopup();
    } else {
      Segment& s = p->segs[transSeg_];
      ImGui::TextColored(ImGui::ColorConvertU32ToFloat4(kAccent), "TRANSITION   Segment %d -> %d", transSeg_ + 1, transSeg_ + 2);
      int type = (int)s.after.type;
      ImGui::SetNextItemWidth(-FLT_MIN);
      if (ImGui::Combo("##ttype", &type, kTransNames, 3)) {
        s.after.type = (TransType)type;
        if (type == 0) s.after.duration = 0.f;
        else if (s.after.duration <= 0.f) s.after.duration = 0.5f;
      }
      buttons_.push_back({"trans:type", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
      if (s.after.type == TransType::Instant) ImGui::BeginDisabled();
      ImGui::SetNextItemWidth(-FLT_MIN);
      ImGui::SliderFloat("##tdur", &s.after.duration, 0.1f, kMaxTransition, "%.2f s");
      buttons_.push_back({"trans:duration", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
      if (s.after.type == TransType::Instant) ImGui::EndDisabled();
      ImGui::TextDisabled(s.after.type == TransType::Instant ? "Hard cut, no pause."
                          : s.after.type == TransType::Hold  ? "Camera waits at this key."
                                                             : "Dips to black around the cut.");
      p->normalise();
      if (ImGui::Button("Done", V(-FLT_MIN, 32.f * ui))) ImGui::CloseCurrentPopup();
      buttons_.push_back({"trans:done", ImGui::GetItemRectMin(), ImGui::GetItemRectMax()});
    }
    ImGui::EndPopup();
  } else if (transOpen_ && !transPending_) {
    transOpen_ = false;
  }
}

}  // namespace cm
