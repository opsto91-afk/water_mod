#include "GlHost.hpp"

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <thread>

#include "imgui.h"
#include "imgui_impl_opengl3.h"

namespace cm {

namespace {

// Additive blending for the night-vision / full-bright passes. The ImGui GL backend
// restores its normal blend state when it meets ImDrawCallback_ResetRenderState.
void additiveBlend(const ImDrawList*, const ImDrawCmd*) { glBlendFunc(GL_ONE, GL_ONE); }

int mapAndroidKey(int code) {
  switch (code) {
    case 67: return ImGuiKey_Backspace;   // KEYCODE_DEL
    case 112: return ImGuiKey_Delete;     // KEYCODE_FORWARD_DEL
    case 66: return ImGuiKey_Enter;       // KEYCODE_ENTER
    case 111: return ImGuiKey_Escape;     // KEYCODE_ESCAPE
    case 61: return ImGuiKey_Tab;         // KEYCODE_TAB
    case 21: return ImGuiKey_LeftArrow;
    case 22: return ImGuiKey_RightArrow;
    case 19: return ImGuiKey_UpArrow;
    case 20: return ImGuiKey_DownArrow;
    default: return -1;
  }
}

void appendUtf8(std::string& s, unsigned cp) {
  if (cp < 0x80) s += (char)cp;
  else if (cp < 0x800) { s += (char)(0xC0 | (cp >> 6)); s += (char)(0x80 | (cp & 0x3F)); }
  else if (cp < 0x10000) { s += (char)(0xE0 | (cp >> 12)); s += (char)(0x80 | ((cp >> 6) & 0x3F)); s += (char)(0x80 | (cp & 0x3F)); }
  else { s += (char)(0xF0 | (cp >> 18)); s += (char)(0x80 | ((cp >> 12) & 0x3F)); s += (char)(0x80 | ((cp >> 6) & 0x3F)); s += (char)(0x80 | (cp & 0x3F)); }
}

// Everything we touch on the game's GL state, so we can put it back afterwards.
struct GlState {
  GLint drawFb = 0, readFb = 0, activeTex = 0, tex2d = 0, vp[4] = {0, 0, 0, 0};
  GLboolean scissor = GL_FALSE, cmask[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
  GLfloat clearCol[4] = {0, 0, 0, 0};

  void save() {
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFb);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFb);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTex);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex2d);
    glGetIntegerv(GL_VIEWPORT, vp);
    scissor = glIsEnabled(GL_SCISSOR_TEST);
    glGetBooleanv(GL_COLOR_WRITEMASK, cmask);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, clearCol);
  }
  void restore() const {
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)drawFb);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)readFb);
    glActiveTexture((GLenum)activeTex);
    glBindTexture(GL_TEXTURE_2D, (GLuint)tex2d);
    glViewport(vp[0], vp[1], vp[2], vp[3]);
    if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
    glColorMask(cmask[0], cmask[1], cmask[2], cmask[3]);
    glClearColor(clearCol[0], clearCol[1], clearCol[2], clearCol[3]);
  }
};

}  // namespace

GlHost& GlHost::instance() {
  static GlHost h;
  return h;
}

void GlHost::configure(Library* lib, Callbacks cb) {
  lib_ = lib;
  cb_ = std::move(cb);
}

bool GlHost::waitShutdown(int timeoutMs) {
  for (int i = 0; i < timeoutMs / 5; ++i) {
    if (shutdownDone_.load()) return true;
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  return shutdownDone_.load();
}

// ------------------------------------------------------------------ input

bool GlHost::onTouch(int action, int pointerId, float x, float y) {
  if (!active_.load()) return false;
  // Only touches that start on our own UI are consumed; everything else reaches the game.
  return touch_.offer(TouchEv{action, pointerId, x, y});
}

bool GlHost::onKey(int code, unsigned unicodeChar, bool down) {
  if (!active_.load()) return false;
  std::lock_guard<std::mutex> l(inMu_);
  const int k = mapAndroidKey(code);
  if (k >= 0) {
    keyQ_.push_back({k, down});
    return true;
  }
  // Some launchers deliver typed characters only as key events. If we have already
  // seen real text-input events, rely on those and ignore this to avoid doubles.
  if (down && unicodeChar >= 32 && !textSeen_) {
    appendUtf8(textQ_, unicodeChar);
    return true;
  }
  return false;
}

bool GlHost::onText(const std::string& utf8) {
  if (!active_.load()) return false;
  std::lock_guard<std::mutex> l(inMu_);
  textSeen_ = true;
  textQ_ += utf8;
  return true;
}

// ----------------------------------------------------------------- lifecycle

bool GlHost::initImGui() {
  if (!lib_) return false;
  IMGUI_CHECKVERSION();
  ImGui::CreateContext();
  ImGuiIO& io = ImGui::GetIO();
  io.IniFilename = nullptr;
  io.LogFilename = nullptr;
  io.ConfigFlags |= ImGuiConfigFlags_NoMouseCursorChange;
  ImFontConfig fc;
  fc.SizePixels = kFontBasePx;  // the editor scales it by screenHeight/540
  io.Fonts->AddFontDefault(&fc);
  if (!ImGui_ImplOpenGL3_Init("#version 300 es")) {
    ImGui::DestroyContext();
    return false;
  }
  editor_ = std::make_unique<Editor>(*lib_, touch_);
  inited_ = true;
  lastNs_ = 0;
  return true;
}

void GlHost::shutdownNow() {
  if (inited_) {
    editor_.reset();
    ImGui_ImplOpenGL3_Shutdown();
    ImGui::DestroyContext();
    inited_ = false;
  }
  if (tex_) {
    glDeleteTextures(1, &tex_);
    tex_ = 0;
  }
  texW_ = texH_ = 0;
  surface_ = nullptr;
  surfaceArea_ = 0;
  shutdownDone_.store(true);
}

void GlHost::ensureTexture(int w, int h) {
  if (tex_ && w == texW_ && h == texH_) return;
  GLint prev = 0;
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev);
  if (tex_) glDeleteTextures(1, &tex_);
  glGenTextures(1, &tex_);
  glBindTexture(GL_TEXTURE_2D, tex_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  // RGB (no alpha): copying from a framebuffer without alpha into RGBA would be GL_INVALID_OPERATION.
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE, nullptr);
  glBindTexture(GL_TEXTURE_2D, (GLuint)prev);
  texW_ = w;
  texH_ = h;
}

// ------------------------------------------------------------------- frame

void GlHost::onSwap(void* display, void* surface) {
  if (shutdownReq_.load()) {
    if (!shutdownDone_.load()) shutdownNow();
    return;
  }
  if (!active_.load()) {
    if (wasActive_ && inited_) {
      touch_.releaseAll(ImGui::GetIO());
      if (kbShown_ && cb_.onKeyboard) cb_.onKeyboard(false);
      kbShown_ = false;
    }
    wasActive_ = false;
    lastNs_ = 0;
    return;
  }
  if (!inited_ && !initImGui()) {
    active_.store(false);
    return;
  }

  EGLint w = 0, h = 0;
  if (!eglQuerySurface((EGLDisplay)display, (EGLSurface)surface, EGL_WIDTH, &w) ||
      !eglQuerySurface((EGLDisplay)display, (EGLSurface)surface, EGL_HEIGHT, &h))
    return;
  if (w < 200 || h < 200) return;  // ignore tiny / off-screen surfaces
  const long long area = (long long)w * h;
  if (surface != surface_ && area >= surfaceArea_) {
    surface_ = surface;
    surfaceArea_ = area;
  }
  if (surface != surface_) return;

  const bool justActivated = !wasActive_;
  wasActive_ = true;
  ImGuiIO& io = ImGui::GetIO();

  // --- 1. grab the game frame that was just rendered ---
  GlState st;
  st.save();
  ensureTexture(w, h);
  glActiveTexture(GL_TEXTURE0);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
  glBindTexture(GL_TEXTURE_2D, tex_);
  glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);

  // --- 2. blank the real screen; the game is now only visible inside our preview box ---
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
  glDisable(GL_SCISSOR_TEST);
  glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
  glViewport(0, 0, w, h);
  glClearColor(0.02f, 0.025f, 0.045f, 1.f);
  glClear(GL_COLOR_BUFFER_BIT);

  // --- 3. feed input, build + draw the UI ---
  {
    std::lock_guard<std::mutex> l(inMu_);
    for (const KeyEv& k : keyQ_) io.AddKeyEvent((ImGuiKey)k.key, k.down);
    keyQ_.clear();
    if (!textQ_.empty()) {
      io.AddInputCharactersUTF8(textQ_.c_str());
      textQ_.clear();
    }
  }
  const long long nowNs =
      std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
  float dt = lastNs_ ? (float)((nowNs - lastNs_) * 1e-9) : 1.f / 60.f;
  lastNs_ = nowNs;
  dt = std::clamp(dt, 1.f / 240.f, 0.1f);

  io.DisplaySize = ImVec2((float)w, (float)h);
  io.DisplayFramebufferScale = ImVec2(1.f, 1.f);
  io.DeltaTime = dt;
  touch_.beginFrame(io);

  ImGui_ImplOpenGL3_NewFrame();
  ImGui::NewFrame();

  EditorHost host;
  host.gameTex = (ImTextureID)(intptr_t)tex_;
  host.gameW = w;
  host.gameH = h;
  host.additiveCb = additiveBlend;
  host.onSave = cb_.onSave;
  host.onClose = cb_.onClose;
  host.onCamera = cb_.onCamera;
  host.readPose = cb_.readPose;
  host.onLive = cb_.onLive;
  host.onFade = cb_.onFade;
  host.onSetRelative = cb_.onSetRelative;
  if (cb_.decorate) cb_.decorate(host);
  if (justActivated && cb_.startPose) editor_->setCamera(cb_.startPose());
  editor_->draw(io.DisplaySize, dt, host);

  ImGui::Render();
  ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());

  // --- 4. soft keyboard follows the text field focus ---
  if (io.WantTextInput != kbShown_) {
    kbShown_ = io.WantTextInput;
    if (cb_.onKeyboard) cb_.onKeyboard(kbShown_);
  }

  st.restore();
}

}  // namespace cm
