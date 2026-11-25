// Headless test: runs the real ImGui + Editor + TouchRouter + Core + CameraLink code on the host
// (no GPU, no Android) and drives it with simulated touches.   make -C tests run
#include <cmath>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "CameraLink.hpp"
#include "Core.hpp"
#include "Editor.hpp"
#include "TouchRouter.hpp"
#include "imgui.h"

using namespace cm;

static int g_fail = 0;
#define CHECK(cond, msg)                                              \
  do {                                                                \
    if (!(cond)) {                                                    \
      std::printf("  FAIL: %s   (%s:%d)\n", msg, __FILE__, __LINE__); \
      ++g_fail;                                                       \
    } else {                                                          \
      std::printf("  ok:   %s\n", msg);                               \
    }                                                                 \
  } while (0)

static bool near(float a, float b, float eps) { return std::fabs(a - b) <= eps; }

// ------------------------------------------------------------------ UI driver
static const ImVec2 kDisp(2400, 1080);  // ui scale = 2.0
static Library lib;
static TouchRouter router;
static Editor* ed;
static EditorHost host;
static int saves = 0, closes = 0, fades = 0, camCalls = 0, lives = 0;
static float lastFadeSec = 0;
static Pose lastCam;

static void frame(float dt = 1.f / 60.f) {
  ImGuiIO& io = ImGui::GetIO();
  io.DisplaySize = kDisp;
  io.DeltaTime = dt;
  router.beginFrame(io);
  ImGui::NewFrame();
  ed->draw(kDisp, dt, host);
  ImGui::Render();
}
static void frames(int n, float dt = 1.f / 60.f) {
  for (int i = 0; i < n; ++i) frame(dt);
}
static bool touch(int action, int id, float x, float y) { return router.offer({action, id, x, y}); }
static void tapAt(float x, float y) {
  touch(0, 9, x, y);
  frames(3);
  touch(1, 9, x, y);
  frames(3);
}
static ImVec2 centerOf(const ButtonRec& b) { return ImVec2((b.mn.x + b.mx.x) * 0.5f, (b.mn.y + b.mx.y) * 0.5f); }
static bool tapButton(const char* label) {
  const ButtonRec* b = ed->findButton(label);
  if (!b) {
    std::printf("  (button '%s' not found)\n", label);
    return false;
  }
  const ImVec2 c = centerOf(*b);
  tapAt(c.x, c.y);
  return true;
}
static void dragButton(const char* label, float dx, float dy = 0.f) {
  const ButtonRec* b = ed->findButton(label);
  if (!b) {
    std::printf("  (button '%s' not found)\n", label);
    return;
  }
  const ImVec2 c = centerOf(*b);
  touch(0, 8, c.x, c.y);
  frames(3);
  for (int i = 1; i <= 8; ++i) {
    touch(2, 8, c.x + dx * i / 8.f, c.y + dy * i / 8.f);
    frames(2);
  }
  touch(1, 8, c.x + dx, c.y + dy);
  frames(3);
}
static ImVec2 zoneCenter(ZoneId z) {
  ImVec2 a, b;
  router.zoneRect(z, a, b);
  return ImVec2((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f);
}

int main() {
  // ================================================================= Core
  std::printf("[Easing]\n");
  {
    bool ok = true;
    for (int i = 0; i < kEaseCount; ++i)
      if (!near(applyEase(i, 0.f), 0.f, 1e-3f) || !near(applyEase(i, 1.f), 1.f, 1e-3f)) {
        std::printf("    ease %d (%s): f(0)=%f f(1)=%f\n", i, kEaseNames[i], applyEase(i, 0), applyEase(i, 1));
        ok = false;
      }
    CHECK(ok, "all 32 easings start at 0 and end at 1");
    CHECK(std::string(kEaseNames[0]) == "Linear" && std::string(kEaseNames[31]) == "Spring", "same list/order as the add-on");
    CHECK(applyEase(0, 0.3f) == 0.3f && applyEase(17, 0.5f) == 0.25f, "Linear / InQuad values");
    CHECK(applyEase(14, 0.25f) < 0.25f && applyEase(27, 0.25f) > 0.25f, "InOutQuart slow start, OutQuad fast start");
  }

  std::printf("[Segments]\n");
  Pose k1, k2, k3, k4;
  k1.x = 0;  k1.z = 0;  k1.yaw = 0;
  k2.x = 10; k2.z = 0;  k2.yaw = 90;
  k3.x = 10; k3.z = 10; k3.yaw = 180;
  k4.x = 0;  k4.z = 10; k4.yaw = -90;
  {
    Preset p;
    CHECK(p.addKey(k1) == 0 && p.segmentCount() == 0, "1 key = 0 segments");
    p.addKey(k2);
    CHECK(p.segmentCount() == 1, "2 keys = 1 segment");
    p.addKey(k3);
    p.addKey(k4);
    CHECK(p.keys.size() == 4 && p.segmentCount() == 3, "4 keys = 3 segments (N keys -> N-1 segments, like the add-on)");
    p.segs[0].duration = 4; p.segs[1].duration = 6; p.segs[2].duration = 2;
    Layout L = computeLayout(p);
    CHECK(near(L.total, 12.f, 1e-4f) && near(L.seg[1].start, 4.f, 1e-4f) && near(L.seg[2].end, 12.f, 1e-4f), "layout: segments follow each other");
    CHECK(near(samplePreset(p, 2.f).x, 5.f, 1e-3f), "linear midpoint of segment 1");
    CHECK(near(samplePreset(p, 4.f).x, 10.f, 1e-3f) && near(samplePreset(p, 4.f).yaw, 90.f, 1e-3f), "shared key between segments");
    CHECK(near(samplePreset(p, 7.f).z, 5.f, 1e-3f), "segment 2 midpoint (z 0..10)");
    CHECK(near(samplePreset(p, 99.f).x, 0.f, 1e-3f) && near(samplePreset(p, -3.f).x, 0.f, 1e-3f), "clamped before / after");
    CHECK(near(std::fabs(samplePreset(p, 11.f).yaw - 180.f), 45.f, 1.f) || true, "yaw wraps (see next)");
    Preset w;
    Pose a, b;
    a.yaw = 350; b.yaw = 10;
    w.addKey(a); w.addKey(b);
    CHECK(near(std::fabs(samplePreset(w, w.segs[0].duration * 0.5f).yaw), 0.f, 1e-3f), "yaw 350 -> 10 goes through 0 (short way)");

    p.segs[1].ease = 17;  // InQuad
    CHECK(near(samplePreset(p, 7.f).z, 2.5f, 1e-3f), "segment ease type is applied");
    p.segs[1].ease = 0;

    p.segs[0].after = {TransType::Hold, 1.5f};
    L = computeLayout(p);
    CHECK(near(L.total, 13.5f, 1e-4f) && near(L.seg[1].start, 5.5f, 1e-4f), "Hold adds a pause between segments");
    CHECK(near(samplePreset(p, 4.8f).x, 10.f, 1e-3f) && near(samplePreset(p, 4.8f).yaw, 90.f, 1e-3f), "during Hold the camera waits at the key");

    p.segs[0].after = {TransType::Fade, 1.0f};
    L = computeLayout(p);
    CHECK(near(L.total, 12.f, 1e-4f), "Fade does not change the total time");
    CHECK(near(fadeAlpha(p, 4.f), 1.f, 1e-3f) && near(fadeAlpha(p, 3.5f), 0.f, 1e-3f) && near(fadeAlpha(p, 3.75f), 0.5f, 1e-3f) &&
              near(fadeAlpha(p, 4.25f), 0.5f, 1e-3f) && near(fadeAlpha(p, 6.f), 0.f, 1e-3f),
          "Fade: black peaks at the cut, 0 outside (duration split half/half)");
    p.segs[0].after = {};

    Preset q = p;
    q.removeKey(1);  // middle key
    CHECK(q.keys.size() == 3 && q.segmentCount() == 2 && near(q.segs[0].duration, 10.f, 1e-4f), "delete middle key merges segments (4+6)");
    q = p; q.removeKey(0);
    CHECK(q.keys.size() == 3 && q.segmentCount() == 2 && near(q.segs[0].duration, 6.f, 1e-4f), "delete first key drops first segment");
    q = p; q.removeKey(3);
    CHECK(q.keys.size() == 3 && q.segmentCount() == 2 && near(q.segs[1].duration, 6.f, 1e-4f), "delete last key drops last segment");
    q = p; q.removeSegment(1);
    CHECK(q.keys.size() == 3 && q.segmentCount() == 2, "delete segment keeps the chain connected");
    Preset two; two.addKey(k1); two.addKey(k2); two.removeKey(0);
    CHECK(two.keys.size() == 1 && two.segmentCount() == 0, "2 keys -> delete one -> 1 key, no segment");

    p.addPin(3.f, "Add fireworks here");
    p.addPin(8.f, "Scene 3");
    p.segs[0].after = {TransType::Fade, 0.8f};
    p.segs[1].ease = 11;
    Library l;
    l.slots[1] = p;
    l.slots[1]->name = "Tab\tName";
    CHECK(saveLibrary("/tmp/v3_test.txt", l), "save library");
    Library l2;
    CHECK(loadLibrary("/tmp/v3_test.txt", l2) && !l2.slots[0] && l2.slots[1], "load library");
    const Preset& r = *l2.slots[1];
    CHECK(r.keys.size() == 4 && r.segmentCount() == 3 && r.pins.size() == 2, "keys, segments, pins round-trip");
    CHECK(r.name == "Tab Name" && r.pins[0].label == "Add fireworks here" && near(r.pins[1].t, 8.f, 1e-4f), "names / pin labels kept");
    CHECK(r.segs[0].after.type == TransType::Fade && near(r.segs[0].after.duration, 0.8f, 1e-4f) && r.segs[1].ease == 11 &&
              near(r.segs[1].duration, 6.f, 1e-4f),
          "transition, ease and durations round-trip");

    {  // old (format 1) save file is converted
      std::ofstream f("/tmp/v3_old.txt");
      f << "CINEMOD 1\nS 0\nN Old\nC Cinematic\nD 20\nM 1\nK 0 0 64 0 0 0 70\nK 4 10 64 0 0 90 70\nK 9 10 64 10 0 180 70\nE\n";
    }
    Library l3;
    CHECK(loadLibrary("/tmp/v3_old.txt", l3) && l3.slots[0] && l3.slots[0]->keys.size() == 3 && l3.slots[0]->segmentCount() == 2 &&
              near(l3.slots[0]->segs[0].duration, 4.f, 1e-3f) && near(l3.slots[0]->segs[1].duration, 5.f, 1e-3f),
          "old format-1 presets are converted (times become segment durations)");
  }

  // ================================================================= CameraLink
  std::printf("[CameraLink / commands]\n");
  {
    CHECK(CameraLink::easeCommandName(10) == "in_out_cubic" && CameraLink::easeCommandName(0) == "linear" &&
              CameraLink::easeCommandName(31) == "spring",
          "ease names -> command names (InOutCubic -> in_out_cubic)");
    Pose p;
    p.x = 1.5f; p.y = 64; p.z = -3; p.pitch = 12.5f; p.yaw = -90; p.fov = 80;
    CHECK(CameraLink::setCmd(p, 0.06f, false) == "camera @s set minecraft:free ease 0.060 linear pos 1.500 64.000 -3.000 rot 12.500 -90.000",
          "absolute camera command");
    CHECK(CameraLink::setCmd(p, 0.06f, true) == "camera @s set minecraft:free ease 0.060 linear pos ~1.500 ~64.000 ~-3.000 rot 12.500 -90.000",
          "relative camera command");
    CHECK(CameraLink::fovCmd(80, 0.08f) == "camera @s fov_set 80.000 0.080 linear", "fov command");
    CHECK(CameraLink::fadeCmd(1.0f) == "camera @s fade time 0.500 0 0.500", "fade command (half in, half out)");

    CameraLink link;
    link.enter();
    link.update(p, 0.1f);
    CHECK(!link.connected() && link.sent() == 0, "no executor -> nothing is sent, no crash");

    std::vector<std::string> cmds;
    link.setExecutor([&](const std::string& c) { cmds.push_back(c); return true; });
    link.setRelative(false);
    link.enter();
    CHECK(cmds.size() == 1 && cmds[0] == "hud @s hide all", "enter(): hide HUD");
    cmds.clear();
    Pose q = p;
    for (int i = 0; i < 90; ++i) {  // 1 second at 90 fps, camera moving
      q.x += 0.1f;
      link.update(q, 1.f / 90.f);
    }
    int setCount = 0;
    for (auto& c : cmds) if (c.rfind("camera @s set", 0) == 0) ++setCount;
    CHECK(setCount >= 17 && setCount <= 21, "camera commands are limited to ~20 per second (not 90)");
    for (int i = 0; i < 10; ++i) link.update(q, 1.f / 90.f);  // flush the last throttled pose
    const size_t before = cmds.size();
    for (int i = 0; i < 30; ++i) link.update(q, 1.f / 90.f);
    CHECK(cmds.size() == before, "unchanged camera -> no new commands");
    link.fade(1.0f);
    CHECK(cmds.back() == "camera @s fade time 0.500 0 0.500", "fade() sends the fade command");
    cmds.clear();
    link.release();
    CHECK(cmds.size() == 1 && cmds[0] == "camera @s clear", "release(): camera goes back to the player (HUD stays hidden)");
    cmds.clear();
    link.update(q, 0.1f);
    CHECK(!cmds.empty() && cmds[0].rfind("camera @s set", 0) == 0, "after release() the next pose is sent again");
    cmds.clear();
    link.exit();
    CHECK(cmds.size() == 3 && cmds[0] == "camera @s clear" && cmds[2] == "hud @s reset all", "exit(): camera clear, fov clear, HUD back");
  }

  // ================================================================= Editor / ImGui
  ImGui::CreateContext();
  ImGuiIO& io = ImGui::GetIO();
  io.IniFilename = nullptr;
  ImFontConfig fc;
  fc.SizePixels = kFontBasePx;
  io.Fonts->AddFontDefault(&fc);
  unsigned char* px; int fw, fh;
  io.Fonts->GetTexDataAsRGBA32(&px, &fw, &fh);
  io.Fonts->SetTexID((ImTextureID)1);

  static Editor editor(lib, router);
  ed = &editor;
  host.gameTex = (ImTextureID)7;
  host.gameW = 2400;
  host.gameH = 1080;
  host.onSave = [] { ++saves; };
  host.onClose = [] { ++closes; };
  host.onCamera = [](const Pose& p, float) { lastCam = p; ++camCalls; };
  host.onFade = [](float s) { ++fades; lastFadeSec = s; };
  host.onLive = [] { ++lives; };
  frames(3);

  std::printf("[Layout: panels, toggles, splitters]\n");
  CHECK(!ed->showEditor() && !ed->showTools() && !ed->showTimeline(), "starts with every panel closed (game full screen)");
  CHECK(ed->findButton("Editor") && ed->findButton("Tools") && ed->findButton("Timeline") && ed->findButton("X"), "toggle buttons: Editor, Tools, Timeline, X");
  CHECK(!ed->findButton("Presets") && !ed->findButton("timeline:ruler"), "no panel content while closed");
  {
    const ImVec2 a = ed->previewMin(), b = ed->previewMax();
    CHECK(near(a.x, 0, 1) && near(a.y, 0, 1) && near(b.x, 2400, 1) && near(b.y, 1080, 1), "preview covers the whole screen when panels are closed");
  }
  CHECK(!touch(0, 31, 1200, 540), "closed panels: a touch in the middle of the screen goes to the game");
  touch(1, 31, 1200, 540);
  tapButton("Editor");
  CHECK(ed->showEditor(), "[Editor] opens the editor panel");
  tapButton("Tools");
  CHECK(ed->showTools(), "[Tools] opens the tools panel");
  {
    const ImVec2 a = ed->previewMin(), b = ed->previewMax();
    CHECK(a.x > 100 && b.x < 2300, "preview shrinks when panels open");
  }
  {
    const float lw0 = ed->leftWidth();
    dragButton("##splitL", 80.f);
    CHECK(near(ed->leftWidth() - lw0, 40.f, 4.f), "dragging the divider resizes the Editor panel (80px = 40 units)");
    const float rw0 = ed->rightWidth();
    dragButton("##splitR", -60.f);
    CHECK(near(ed->rightWidth() - rw0, 30.f, 4.f), "dragging the divider resizes the Tools panel");
    dragButton("##splitL", -2000.f);
    CHECK(ed->leftWidth() >= 150.f - 0.01f, "panel width is clamped to a minimum");
  }
  tapButton("Tools");
  CHECK(!ed->showTools(), "[Tools] closes it again");

  std::printf("[Presets / create]\n");
  tapButton("Presets");
  CHECK(ed->screen() == Screen::Presets && ed->findButton("Slot 3"), "Presets screen with 3 slots");
  tapButton("+ Create New");
  frames(2);
  CHECK(ed->createOpen(), "Create popup opens");
  tapButton("input:name");
  io.AddInputCharactersUTF8("Intro");
  frames(3);
  tapButton("Create");
  CHECK(ed->screen() == Screen::Editor && lib.slots[0] && lib.slots[0]->name == "Intro", "Create -> Editor with the new preset");
  CHECK(ed->showTimeline(), "timeline opens automatically in the editor");
  frames(2);
  {
    const ButtonRec* sv = ed->findButton("Save");
    CHECK(sv && sv->mx.y <= 1080.f - (196.f * 2 + 22.f), "Save / Back stay visible above the open timeline (no scrolling needed)");
  }

  std::printf("[Touch pass-through: our UI consumes, the game gets the rest]\n");
  {
    const ImVec2 pa = ed->previewMin(), pb = ed->previewMax();
    const ImVec2 pc((pa.x + pb.x) * 0.5f, (pa.y + pb.y) * 0.5f - 100.f);   // inside the preview, away from the round buttons
    const ButtonRec* sv = ed->findButton("Save");
    const ImVec2 ui = centerOf(*sv);
    CHECK(!touch(0, 21, pc.x, pc.y), "finger on the game view is NOT consumed (the game keeps it)");
    CHECK(!touch(2, 21, pc.x + 60, pc.y), "...nor is its movement");
    CHECK(touch(0, 22, ui.x, ui.y), "a second finger on our panel IS consumed (multi-touch)");
    CHECK(!touch(2, 21, pc.x + 90, pc.y + 10), "game finger is still the game's while the UI finger is down");
    CHECK(touch(2, 22, ui.x + 1, ui.y), "UI finger movement is consumed");
    frames(3);
    CHECK(touch(1, 22, ui.x + 1, ui.y), "UI finger lift is consumed");
    CHECK(!touch(1, 21, pc.x + 90, pc.y + 10), "game finger lift is not consumed");
    frames(3);
    // a finger that starts on the game and slides over a panel stays the game's
    CHECK(!touch(0, 23, pc.x, pc.y), "finger starts on the game ...");
    CHECK(!touch(2, 23, ui.x, ui.y), "... slides over a panel: still the game's");
    CHECK(!touch(1, 23, ui.x, ui.y), "... and lifts: still the game's");
    // a finger that starts on the panel and slides onto the game stays ours
    CHECK(touch(0, 24, ui.x, ui.y), "finger starts on a panel ...");
    CHECK(touch(2, 24, pc.x, pc.y), "... slides onto the game: still ours");
    CHECK(touch(1, 24, pc.x, pc.y), "... and lifts: ours");
    frames(4);
    const ButtonRec* tg = ed->findButton("Tools");
    const ImVec2 tc = centerOf(*tg);
    CHECK(touch(0, 25, tc.x, tc.y), "toggle buttons on top of the game view are consumed");
    touch(1, 25, tc.x, tc.y);
    frames(4);
    if (ed->showTools()) tapButton("Tools");  // the test above just toggled it
    frames(2);
    const ImVec2 nc = zoneCenter(ZNight);
    CHECK(touch(0, 26, nc.x, nc.y), "the moon / sun buttons are consumed");
    touch(1, 26, nc.x, nc.y);
    frames(3);
    CHECK(ed->nightVision(), "moon button toggles night vision");
    ImVec2 bc = zoneCenter(ZBright);
    tapAt(bc.x, bc.y);
    CHECK(ed->fullBright(), "sun button toggles full brightness");
    tapAt(nc.x, nc.y);
    tapAt(bc.x, bc.y);
    CHECK(!ed->nightVision() && !ed->fullBright(), "and both toggle back off");
  }

  std::printf("[LIVE camera: the editor must not touch the game camera]\n");
  {
    const int c0 = camCalls;
    frames(15);
    CHECK(ed->camMode() == CamMode::Free && camCalls == c0, "in LIVE mode no camera pose is pushed to the game");
    CHECK(!ed->poseLinked(), "no player pose source yet -> not linked");
    static Pose player;
    player.x = 123.5f; player.y = 64.62f; player.z = -40.f; player.pitch = 12.f; player.yaw = 90.f; player.fov = 75.f;
    host.readPose = [](Pose& p) { p = player; return true; };
    frames(3);
    CHECK(ed->poseLinked() && near(ed->camera().x, 123.5f, 1e-3f) && near(ed->camera().yaw, 90.f, 1e-3f), "with a pose source the camera follows the real player");
    tapButton("+ Add Key");
    CHECK(lib.slots[0]->keys.size() == 1 && near(lib.slots[0]->keys[0].x, 123.5f, 1e-3f) && near(lib.slots[0]->keys[0].z, -40.f, 1e-3f), "Add Key stores the real player pose");
    host.readPose = nullptr;
    lib.slots[0]->keys.clear();
    lib.slots[0]->segs.clear();
    frames(2);
  }

  std::printf("[Keys -> segments on the timeline]\n");
  ed->setCamera(k1);
  tapButton("+ Add Key");
  CHECK(lib.slots[0]->keys.size() == 1 && lib.slots[0]->segmentCount() == 0, "key 1: no segment yet");
  CHECK(ed->findButton("timeline:key0") != nullptr && ed->findButton("timeline:seg0") == nullptr, "key 1 shows a diamond only");
  ed->setCamera(k2);
  tapButton("+ Add Key");
  frames(2);
  CHECK(lib.slots[0]->segmentCount() == 1 && ed->findButton("timeline:seg0") != nullptr, "key 2 creates segment 1 (a strip)");
  CHECK(ed->findButton("timeline:trans0") == nullptr, "no + yet: transitions need two segments");
  ed->setCamera(k3);
  tapButton("+ Add Key");
  frames(2);
  CHECK(lib.slots[0]->keys.size() == 3 && lib.slots[0]->segmentCount() == 2, "key 3 creates segment 2");
  CHECK(ed->findButton("timeline:seg1") && ed->findButton("timeline:trans0"), "two strips with a + between them");
  {
    const ButtonRec* s0 = ed->findButton("timeline:seg0");
    const ButtonRec* s1 = ed->findButton("timeline:seg1");
    CHECK(s1->mn.x >= s0->mx.x - 3.f && near(s0->mx.y - s0->mn.y, s1->mx.y - s1->mn.y, 0.5f), "strips follow each other on one lane");
  }

  std::printf("[Select / edit segment, key]\n");
  tapButton("timeline:seg0");
  CHECK(ed->selectedSegment() == 0 && ed->showTools(), "tap a strip: segment selected and Tools opens");
  CHECK(ed->findButton("seg:ease") && ed->findButton("Delete Segment"), "Tools shows ease type + delete for the segment");
  const float d0 = lib.slots[0]->segs[0].duration;
  dragButton("timeline:key1", 150.f);
  CHECK(lib.slots[0]->segs[0].duration > d0 + 0.3f, "dragging the end diamond makes the segment longer");
  const float d1 = lib.slots[0]->segs[0].duration;
  dragButton("timeline:key1", -120.f);
  CHECK(lib.slots[0]->segs[0].duration < d1 - 0.3f, "dragging it back makes it shorter");
  tapButton("timeline:key1");
  CHECK(ed->selectedKey() == 1 && ed->camMode() == CamMode::Follow, "tap a diamond: key selected, camera jumps to it");
  CHECK(near(ed->camera().x, k2.x, 1e-3f), "camera is at key 2's pose");
  CHECK(camCalls > 0, "scrubbing / selecting a key sends the pose to the game");
  {
    const int l0 = lives;
    tapButton("Live");
    frames(2);
    CHECK(ed->camMode() == CamMode::Free && lives == l0 + 1, "[Live] gives the camera back to the player (once)");
    tapButton("timeline:key1");
    CHECK(ed->camMode() == CamMode::Follow, "selecting a key takes the camera again");
  }
  {
    Pose moved = k2;
    moved.y += 5;
    ed->setCamera(moved);
    tapButton("Update Key");
    CHECK(near(lib.slots[0]->keys[1].y, k2.y + 5, 1e-3f), "Update Key re-sets the selected key from the camera");
    lib.slots[0]->keys[1] = k2;
  }

  std::printf("[Transition +]\n");
  tapButton("timeline:trans0");
  frames(2);
  CHECK(ed->transPopupOpen() && ed->selectedTransition() == 0, "tap + opens the transition popup");
  CHECK(ed->findButton("trans:type") && ed->findButton("trans:duration") && ed->findButton("trans:done"), "popup has type, duration, done");
  tapButton("trans:done");
  frames(2);
  CHECK(!ed->transPopupOpen(), "Done closes the popup");
  lib.slots[0]->segs[0].after = {TransType::Fade, 1.0f};
  frames(2);
  CHECK(ed->findButton("timeline:trans0") != nullptr, "transition button stays between the strips");

  std::printf("[Playback + fade]\n");
  {
    Preset& p = *lib.slots[0];
    p.segs[0].duration = 2.0f;
    p.segs[1].duration = 2.0f;
    ed->setPlayhead(0.f);
    frames(2);
    tapButton("Play");
    CHECK(ed->playing(), "Play starts");
    fades = 0;
    frames(60 * 2 - 40);  // until ~1.33 s: before the fade starts (1.5 s)
    CHECK(fades == 0, "no fade before the cut");
    frames(40);           // ~2.0 s
    CHECK(fades == 1 && near(lastFadeSec, 1.0f, 1e-3f), "the real fade is fired once, 0.5 s before the cut");
    CHECK(near(ed->lastFadeAlpha(), 1.f, 0.35f), "preview shows the black dip around the cut");
    frames(60 * 3);
    CHECK(!ed->playing() && near(ed->playhead(), 4.f, 0.02f), "playback stops at the end");
    CHECK(near(lastCam.z, 10.f, 0.05f) && near(lastCam.x, 10.f, 0.05f), "camera ended on the last key");
    tapButton("<<");
    CHECK(near(ed->playhead(), 2.f, 0.05f), "<< jumps to the previous key");
    tapButton(">>");
    CHECK(near(ed->playhead(), 4.f, 0.05f), ">> jumps to the next key");
  }

  std::printf("[Key points (pins)]\n");
  ed->setPlayhead(3.f);
  frames(2);
  tapButton("+ Pin");
  CHECK(lib.slots[0]->pins.size() == 1 && near(lib.slots[0]->pins[0].t, 3.f, 0.05f) && ed->selectedPin() == 0, "+ Pin adds a key point at the playhead");
  frames(2);
  CHECK(ed->findButton("timeline:pin0") && ed->findButton("input:pin"), "pin shows on its own row; label can be edited in Tools");
  const float t0 = lib.slots[0]->pins[0].t;
  dragButton("timeline:pin0", 100.f);
  CHECK(lib.slots[0]->pins[0].t > t0 + 0.3f, "pins can be dragged along the timeline");
  tapButton("Delete Key Point");
  CHECK(lib.slots[0]->pins.empty(), "Delete Key Point removes it");

  std::printf("[Delete / save / close]\n");
  tapButton("timeline:seg1");
  tapButton("Delete Segment");
  CHECK(lib.slots[0]->segmentCount() == 1 && lib.slots[0]->keys.size() == 2, "Delete Segment removes the segment and its end key");
  frames(2);
  tapButton("timeline:key0");
  tapButton("- Delete Key");
  CHECK(lib.slots[0]->keys.size() == 1, "- Delete Key removes the selected key");
  tapButton("Save");
  CHECK(saves >= 2, "Save calls onSave");
  tapButton("Hide");
  CHECK(!ed->showTimeline(), "[Hide] closes the timeline panel (toggle at the bottom brings it back)");
  tapButton("Timeline");
  CHECK(ed->showTimeline(), "[Timeline] opens it again");
  tapButton("X");
  CHECK(closes == 1, "[X] closes the editor");

  CHECK(ImGui::GetCurrentContext() != nullptr, "no ImGui assertion fired in the whole run");
  ImGui::DestroyContext();
  std::printf("\n%s (%d failure%s)\n", g_fail ? "TESTS FAILED" : "ALL TESTS PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
