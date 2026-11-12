// PovBridge.cpp - implementation of the Screen POV camera drive.
//
// v1.4 (2026-11-13): XYZ-position override ONLY, rewritten after the user's on-device RE.
//   Device dumps (Zaphkiel RE of the on-device libminecraftpe.so, run 2026-11-13) identified
//   THE two functions that write the renderer's pose float3 (LevelRendererPlayer + 0x614):
//     * fn_0xb7d4274 "CamTick"   - the PER-FRAME camera-pose function: locks a global mutex,
//       eases the pose (fn_0xb7a9370) or posts the "moveRenderingArea task" for big jumps,
//       and WRITES the final pose at LRP+0x614..+0x61f on EVERY frame.
//     * fn_0xb7c7268 "CamSetter" - the immediate pose-set funnel (camera packet/script path):
//       calls the ease core fn_0xb7a8c50 and copies poseIn onto LRP+0x614/+0x61c/+0x620/+0x628.
//   v1.3 failed on device because it injected BEFORE the original RenderLevel - the tick then
//   OVERWROTE +0x614 with vanilla's eased pose before anything consumed it.
// v1.4 design:
//   1. UpdatePlayerFromCamera  -> SKIPPED while override ON (body never syncs from the camera)
//   2. LocalPlayerApplyTurnDelta -> PASS-THROUGH while override ON (normal look works)
//   3. RenderLevel             -> diagnostic only (logs the live LRP+0x614 value, no writes)
//   4. CamTick                 -> wrapped: ORIGINAL runs first (vanilla pose written as usual),
//                                 then - only if the override is ON - ONLY the 12-byte float3
//                                 at LRP+0x614 is overwritten (relative: live pose + offset,
//                                 fixed: absolute world XYZ). Orientation/FOV bytes untouched.
//   5. CamSetter               -> same post-orig overwrite (covers the immediate packet path).
//   Vanilla re-writes +0x614 every tick, so OFF needs NO restore snapshot/hack.
//   Every step is logged to pov_mod.log.
#include "PovBridge.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <ucontext.h>
#include <unistd.h>
#include <sys/mman.h>

#include <pl/memory/Hook.hpp>
#include <pl/memory/Signature.hpp>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <array>
#include <cstdint>

namespace pov {
namespace {

using Clock = std::chrono::steady_clock;

constexpr const char* kTag = "ScreenPov";
constexpr const char* kLibName = "libminecraftpe.so";
constexpr const char* kKurumiLog =
    "/storage/emulated/0/Android/media/org.levimc.launcher/kurumi/pov_mod.log";
constexpr const char* kLogName = "pov_mod.log";

// ---- signatures (same strings BedrockTools 60.4 uses for Freelook / render capture) ----
// UpdatePlayerFromCamera  -> the per-frame player/camera sync (Freelook hooks this too, and
//                            SKIPS the original while freelook is active).
// LocalPlayerApplyTurnDelta -> the mouse/touch look delta. Since v1.3 it is a PASS-THROUGH
//                            (the view is free; only the position is pinned). It stays hooked
//                            so toggling back is cheap and the install log keeps its shape.
// RenderLevel             -> LevelRenderer::render (BreadCrumbs/MotionBlur hook this too).
//                            v1.4: diagnostic only (no position injection anymore).
// CamTick                 -> fn_0xb7d4274 (Zaphkiel): the per-frame POSE writer of LRP+0x614.
// CamSetter               -> fn_0xb7c7268 (Zaphkiel): the immediate pose funnel (packet path).
//       Both cam sigs are on-device UNIQUE (14 insns each, matches=1).
constexpr std::string_view kSigUpdatePlayerFromCamera =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? A9 ? ? ? 91 54 D0 3B D5 ? ? ? 52";
constexpr std::string_view kSigLocalPlayerApplyTurnDelta =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 56 D0 3B D5 F3 03 00 AA F4 03 01 AA ? ? ? F9 ? ? ? F8 ? ? ? F9 ? ? ? F9";
constexpr std::string_view kSigRenderLevel =
    "? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5";

constexpr std::string_view kSigCamTick =
    "FF C3 06 D1 EA A3 00 FD E9 23 15 6D FD 7B 16 A9 FC 67 17 A9 F8 5F 18 A9 F6 57 19 A9 F4 4F 1A A9 FD 83 05 91 57 D0 3B D5 ? ? ? F0 94 02 02 91 E8 ? ? F9 F3 03 00 AA";
constexpr std::string_view kSigCamSetter =
    "FD 7B BE A9 F4 4F 01 A9 FD 03 00 91 F3 03 01 AA F4 03 00 AA ? ? ? 97 60 06 40 FD 61 12 40 BD 88 52 18 91 00 01 00 FD 81 1E 06 BD 60 42 41 FC 61 1E 40 BD 80 12 03 FD";

// v1.4: the CameraComponent entt hash is long gone - the override writes the renderer's own
// LevelRendererPlayer position float3 directly (verified on device). No entt anywhere.

// ---- offsets (Bedrock 60.4, from QYCottage/BedrockTools sdk/offsets, MIT) -------------
namespace off {
inline constexpr std::size_t Actor_mStateVectorComponent = 0x228;
inline constexpr std::size_t Actor_mRotationComponent = 0x238;
inline constexpr std::size_t LevelRenderer_mLevelRendererPlayer = 0x430;
inline constexpr std::size_t LevelRendererPlayer_mCamPos = 0x614;  // float3 camera position
}  // namespace off

// ---- file log + crash tracer -----------------------------------------------------------
struct LoggerState {
  int fd = -1;
  std::mutex mu;
  std::string path;
};
LoggerState g_log;

void flog(const std::string& s) {
  std::lock_guard<std::mutex> lk(g_log.mu);
  if (g_log.fd >= 0) {
    char ts[16] = "--:--:--";
    std::time_t t = std::time(nullptr);
    std::tm tmv{};
    if (localtime_r(&t, &tmv)) std::strftime(ts, sizeof ts, "%H:%M:%S", &tmv);
    std::string line = std::string(ts) + " " + s + "\n";
    (void)!::write(g_log.fd, line.data(), line.size());
    ::fsync(g_log.fd);
  }
  __android_log_print(ANDROID_LOG_INFO, kTag, "%s", s.c_str());
}

bool memMapped(const void* addr, size_t len) {
  if (!addr) return false;
  long ps = sysconf(_SC_PAGESIZE);
  if (ps <= 0) return false;
  uintptr_t a = reinterpret_cast<uintptr_t>(addr);
  uintptr_t base = a & ~(uintptr_t)(ps - 1);
  size_t pages = (a + len - base + (size_t)ps - 1) / (size_t)ps;
  std::vector<unsigned char> vec(pages);
  return mincore(reinterpret_cast<void*>(base), pages * (size_t)ps, vec.data()) == 0;
}

namespace crash {
int g_fd = -1;
std::atomic<uintptr_t> g_mcBase{0}, g_mcEnd{0};
char g_stage[128] = "init";
struct sigaction g_old[SIGSEGV + 1];
const int kSigs[] = {SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE};

void setStage(const char* s) {
  std::strncpy(g_stage, s, sizeof g_stage - 1);
  g_stage[sizeof g_stage - 1] = 0;
}
void setLib(uintptr_t base, uint64_t size) { g_mcBase.store(base); g_mcEnd.store(base + size); }
void setFd(int fd) { g_fd = fd; }

void put(const char* s) { if (g_fd >= 0) (void)!::write(g_fd, s, std::strlen(s)); }
void putHex(uint64_t v) {
  char b[19] = "0x0000000000000000";
  for (int i = 17; i >= 2; --i, v >>= 4) b[i] = "0123456789abcdef"[v & 0xF];
  put(b);
}

void onSignal(int sig, siginfo_t* info, void* /*uc*/) {
  put("\n===== CRASH (Screen POV) =====\n");
  put("signal: ");
  char nb[8];
  std::snprintf(nb, sizeof nb, "%d", sig);
  put(nb);
  put("\nfault address: ");
  putHex(reinterpret_cast<uint64_t>(info ? info->si_addr : 0));
  put("\nstage: ");
  put(g_stage);
  put("\nmc base: ");
  putHex(g_mcBase.load());
  put("\n===============================\n");
  if (g_fd >= 0) ::fsync(g_fd);
  if (g_old[sig].sa_sigaction) g_old[sig].sa_sigaction(sig, info, nullptr);
}

void install() {
  struct sigaction sa{};
  sa.sa_sigaction = onSignal;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);
  for (int sig : kSigs) sigaction(sig, &sa, &g_old[sig]);
}
}  // namespace crash

struct LibMap { uintptr_t base = 0; uint64_t size = 0; };

bool findLib(LibMap& out) {
  FILE* f = std::fopen("/proc/self/maps", "r");
  if (!f) return false;
  char line[1024];
  bool found = false;
  uintptr_t lo = 0, hi = 0;
  while (std::fgets(line, sizeof line, f)) {
    if (!std::strstr(line, kLibName)) continue;
    unsigned long a = 0, b = 0, off_ = 0;
    char perms[8] = {};
    if (std::sscanf(line, "%lx-%lx %7s %lx", &a, &b, perms, &off_) != 4) continue;
    if (!found) {
      if (off_ != 0) continue;
      lo = a; hi = b; found = true;
    } else if (b > hi) {
      hi = b;
    }
  }
  std::fclose(f);
  if (!found) return false;
  out.base = lo;
  out.size = hi - lo;
  return true;
}

// ---- game state -------------------------------------------------------------------------
std::atomic<void*> g_player{nullptr};

std::mutex g_mu;
PovRequest g_req{};
std::atomic<bool> g_override{false};
std::atomic<bool> g_hooksInstalled{false};
std::atomic<bool> g_started{false};
std::atomic<bool> g_stop{false};
std::thread g_thread;

std::atomic<unsigned long> g_diagUpdate{0}, g_diagWrites{0},
    g_diagRenderLevel{0}, g_diagInjectFail{0}, g_diagTick{0}, g_diagSetter{0};
std::atomic<bool> g_diagUpdateFirst{false}, g_diagRenderFirst{false};
Clock::time_point g_diagBeat = Clock::now();

void* rotationComponent(void* player) {
  if (!player) return nullptr;
  if (!memMapped(static_cast<char*>(player) + off::Actor_mRotationComponent, 8)) return nullptr;
  return *reinterpret_cast<void**>(static_cast<char*>(player) + off::Actor_mRotationComponent);
}
void* stateVectorComponent(void* player) {
  if (!player) return nullptr;
  if (!memMapped(static_cast<char*>(player) + off::Actor_mStateVectorComponent, 8)) return nullptr;
  return *reinterpret_cast<void**>(static_cast<char*>(player) + off::Actor_mStateVectorComponent);
}
// reads the player's feet position + rotation into out (best effort)
bool readPlayerEyeLocked(Pose& out) {
  void* player = g_player.load(std::memory_order_acquire);
  if (!player) return false;
  bool ok = false;
  if (void* rot = rotationComponent(player)) {
    if (memMapped(rot, 8)) {
      float two[2];
      std::memcpy(two, rot, sizeof two);
      out.pitch = two[0];
      out.yaw = two[1];
      ok = true;
    }
  }
  if (void* svc = stateVectorComponent(player)) {
    if (memMapped(svc, 16)) {
      float three[3];
      std::memcpy(three, svc, sizeof three);
      out.x = three[0];
      out.y = three[1] + kEyeHeight;
      out.z = three[2];
      ok = true;
    }
  }
  return ok;
}

void diagBeat(const char* tag) {
  const auto now = Clock::now();
  if (now - g_diagBeat < std::chrono::seconds(2)) return;
  g_diagBeat = now;
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  char b[256];
  std::snprintf(b, sizeof b,
                "%s override=%d update=%lu renderLevel=%lu tick=%lu setter=%lu writes=%lu injectFail=%lu want(pos=%.1f,%.1f,%.1f rel=%d)",
                tag, g_override.load() ? 1 : 0, g_diagUpdate.load(), g_diagRenderLevel.load(),
                g_diagTick.load(), g_diagSetter.load(), g_diagWrites.load(), g_diagInjectFail.load(),
                r.px, r.py, r.pz, r.relativePos ? 1 : 0);
  flog(b);
}

// ---- direct LevelRendererPlayer position override (v1.4) ----------------------------------
// Device REZ (user's Zaphkiel dump): fn_0xb7d4274 (CamTick) WRITES the pose every frame;
// fn_0xb7c7268 (CamSetter) funnels immediate packet poses. v1.4 overwrites ONLY the 12-byte
// float3 at LRP+0x614 AFTER the original ran - vanilla rewrites +0x614 every tick, so OFF
// needs no restore logic at all.
bool applyCamOverride(void* lrp) {
  if (!lrp || !memMapped(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, 12)) {
    g_diagInjectFail.fetch_add(1, std::memory_order_relaxed);
    return false;
  }
  float cur[3];
  std::memcpy(cur, static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, sizeof cur);
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  float target[3];
  composeCamPos(r, cur, target);
  std::memcpy(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, target, sizeof target);
  g_diagWrites.fetch_add(1, std::memory_order_relaxed);
  static std::atomic<bool> firstInjectLog{false};
  if (!firstInjectLog.exchange(true)) {
    char b[256];
    std::snprintf(b, sizeof b,
                  "override: FIRST LRP+0x614 write cur=(%.3f,%.3f,%.3f) target=(%.3f,%.3f,%.3f) rel=%d",
                  cur[0], cur[1], cur[2], target[0], target[1], target[2], r.relativePos ? 1 : 0);
    flog(b);
  }
  return true;
}

// ---- frame hooks -------------------------------------------------------------------------
using UpdatePlayerFromCameraFn = void (*)(void*, void*, void*);
using TurnDeltaFn = void (*)(void*, void*);
using RenderLevelFn = void (*)(void*, void*, void*);
using CamTickFn = void (*)(void*, void*);    // fn_0xb7d4274(this=LRP, flagHolder)
using CamSetterFn = void (*)(void*, void*);  // fn_0xb7c7268(this=LRP, poseIn)
UpdatePlayerFromCameraFn g_origUpdatePlayer = nullptr;
TurnDeltaFn g_origTurnDelta = nullptr;
RenderLevelFn g_origRenderLevel = nullptr;
CamTickFn g_origTick = nullptr;
CamSetterFn g_origSetter = nullptr;

void updatePlayerDetour(void* camera, void* component, void* player) {
  g_diagUpdate.fetch_add(1, std::memory_order_relaxed);
  if (!g_diagUpdateFirst.exchange(true)) {
    char b[256];
    std::snprintf(b, sizeof b, "updatePlayerDetour FIRST call: camera=%p component=%p player=%p", camera, component, player);
    flog(b);
  }
  if (player) g_player.store(player, std::memory_order_release);
  if (g_override.load(std::memory_order_acquire)) {
    // Freelook does the same: SKIP the original sync (the body never chases the camera).
    crash::setStage("updatePlayer:override");
    diagBeat("override");
    return;
  }
  crash::setStage("updatePlayer:original");
  if (g_origUpdatePlayer) g_origUpdatePlayer(camera, component, player);
}

void turnDeltaDetour(void* player, void* delta) {
  if (player) g_player.store(player, std::memory_order_release);
  // Pass-through (since v1.3). The view is free (look works normally); only the camera
  // position is pinned by the CamTick/CamSetter detours. The hook stays installed so toggling is cheap and the
  // install log keeps its shape.
  crash::setStage("turnDelta:original");
  if (g_origTurnDelta) g_origTurnDelta(player, delta);
}

void camTickDetour(void* lrp, void* flagHolder) {
  g_diagTick.fetch_add(1, std::memory_order_relaxed);
  static std::atomic<bool> firstTick{false};
  if (!firstTick.exchange(true)) {
    char b[256];
    std::snprintf(b, sizeof b, "camTickDetour FIRST call: lrp=%p flagHolder=%p", lrp, flagHolder);
    flog(b);
  }
  if (g_origTick) g_origTick(lrp, flagHolder);
  if (!g_override.load(std::memory_order_acquire)) return;
  crash::setStage("tick:override");
  applyCamOverride(lrp);
}

void camSetterDetour(void* lrp, void* pose) {
  g_diagSetter.fetch_add(1, std::memory_order_relaxed);
  static std::atomic<bool> firstSetter{false};
  if (!firstSetter.exchange(true)) {
    char b[256];
    std::snprintf(b, sizeof b, "camSetterDetour FIRST call: lrp=%p pose=%p", lrp, pose);
    flog(b);
  }
  if (g_origSetter) g_origSetter(lrp, pose);
  if (!g_override.load(std::memory_order_acquire)) return;
  crash::setStage("setter:override");
  applyCamOverride(lrp);
}

void renderLevelDetour(void* renderer, void* context, void* view) {
  g_diagRenderLevel.fetch_add(1, std::memory_order_relaxed);
  if (!g_diagRenderFirst.exchange(true)) {
    char b[256];
    std::snprintf(b, sizeof b, "renderLevelDetour FIRST call: renderer=%p context=%p view=%p", renderer, context, view);
    flog(b);
  }
  // Diagnostic: read the camera position the renderer recorded (LevelRenderer+0x430 -> LRP+0x614).
  if (renderer && memMapped(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer, 8)) {
    void* lrp = *reinterpret_cast<void**>(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer);
    if (lrp && memMapped(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, 12)) {
      float cam[3];
      std::memcpy(cam, static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, sizeof cam);
      static std::atomic<unsigned long> camBeat{0};
      const unsigned long n = camBeat.fetch_add(1, std::memory_order_relaxed);
      if (n % 120 == 0) {  // ~every 2s at 60fps
        char b[160];
        std::snprintf(b, sizeof b, "renderer camera pos (LRP+0x614): %.3f %.3f %.3f", cam[0], cam[1], cam[2]);
        flog(b);
      }
    }
  }

  // v1.4: no writes here - the CamTick/CamSetter hooks own the pose override. This hook
  // stays installed for the live "renderer camera pos" log (shows our overridden value).
  crash::setStage("renderLevel:orig");
  if (g_origRenderLevel) g_origRenderLevel(renderer, context, view);
}

// ---- lifecycle ---------------------------------------------------------------------------
struct Resolved { uintptr_t updatePlayerFromCamera = 0, localPlayerApplyTurnDelta = 0, renderLevel = 0, camTick = 0, camSetter = 0; };
Resolved g_resolved;

bool resolveSignatures() {
  const std::string lib(kLibName);
  const std::vector<std::string> patterns = {std::string(kSigUpdatePlayerFromCamera),
                                             std::string(kSigLocalPlayerApplyTurnDelta),
                                             std::string(kSigRenderLevel),
                                             std::string(kSigCamTick),
                                             std::string(kSigCamSetter)};
  const auto found = pl::memory::resolveSignatures(patterns, lib.c_str());
  auto get = [&](std::string_view p) -> uintptr_t {
    const auto it = found.find(std::string(p));
    return it == found.end() ? 0 : it->second;
  };
  g_resolved.updatePlayerFromCamera = get(kSigUpdatePlayerFromCamera);
  g_resolved.localPlayerApplyTurnDelta = get(kSigLocalPlayerApplyTurnDelta);
  g_resolved.renderLevel = get(kSigRenderLevel);
  g_resolved.camTick = get(kSigCamTick);
  g_resolved.camSetter = get(kSigCamSetter);
  return g_resolved.updatePlayerFromCamera != 0 && g_resolved.localPlayerApplyTurnDelta != 0;
}

void unhookAll() {
  crash::setStage("unhook");
  if (g_origRenderLevel) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.renderLevel), reinterpret_cast<void*>(renderLevelDetour));
    g_origRenderLevel = nullptr;
  }
  if (g_origTick) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.camTick), reinterpret_cast<void*>(camTickDetour));
    g_origTick = nullptr;
  }
  if (g_origSetter) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.camSetter), reinterpret_cast<void*>(camSetterDetour));
    g_origSetter = nullptr;
  }
  if (g_origUpdatePlayer) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.updatePlayerFromCamera), reinterpret_cast<void*>(updatePlayerDetour));
    g_origUpdatePlayer = nullptr;
  }
  if (g_origTurnDelta) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.localPlayerApplyTurnDelta), reinterpret_cast<void*>(turnDeltaDetour));
    g_origTurnDelta = nullptr;
  }
  g_hooksInstalled.store(false);
  flog("hooks removed");
}

void watchLoop() {
  for (int attempt = 0; attempt < 240; ++attempt) {
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    if (g_stop.load(std::memory_order_relaxed)) return;
    LibMap lib;
    if (!findLib(lib)) {
      if (attempt == 0) flog("waiting for libminecraftpe.so ...");
      continue;
    }
    crash::setLib(lib.base, lib.size);
    char buf[160];
    std::snprintf(buf, sizeof buf, "libminecraftpe.so found, base=0x%llx - resolving signatures",
                  static_cast<unsigned long long>(lib.base));
    flog(buf);
    crash::setStage("resolve");
    if (!resolveSignatures()) {
      flog("SIGNATURE RESOLUTION FAILED - Screen POV stays INACTIVE (check the log for the missing one)");
    if (!g_resolved.updatePlayerFromCamera) flog("  missing: UpdatePlayerFromCamera");
    if (!g_resolved.localPlayerApplyTurnDelta) flog("  missing: LocalPlayerApplyTurnDelta");
    if (!g_resolved.renderLevel) flog("  missing: RenderLevel (diagnostic log lost; pose drive unaffected)");
    if (!g_resolved.camTick) flog("  missing: CamTick (fn_0xb7d4274) - the camera WILL NOT move");
    if (!g_resolved.camSetter) flog("  missing: CamSetter (fn_0xb7c7268) - packet pose path not overridden");
      return;
    }
    crash::setStage("hooks");
    const bool r2 = pl::memory::hook(reinterpret_cast<void*>(g_resolved.updatePlayerFromCamera),
                                     reinterpret_cast<void*>(updatePlayerDetour),
                                     reinterpret_cast<void**>(&g_origUpdatePlayer)) == 0;
    const bool r3 = pl::memory::hook(reinterpret_cast<void*>(g_resolved.localPlayerApplyTurnDelta),
                                     reinterpret_cast<void*>(turnDeltaDetour),
                                     reinterpret_cast<void**>(&g_origTurnDelta)) == 0;
    const bool r4 = !g_resolved.renderLevel
                        ? false
                        : pl::memory::hook(reinterpret_cast<void*>(g_resolved.renderLevel),
                                           reinterpret_cast<void*>(renderLevelDetour),
                                           reinterpret_cast<void**>(&g_origRenderLevel)) == 0;
    const bool r5 = !g_resolved.camTick
                        ? false
                        : pl::memory::hook(reinterpret_cast<void*>(g_resolved.camTick),
                                           reinterpret_cast<void*>(camTickDetour),
                                           reinterpret_cast<void**>(&g_origTick)) == 0;
    const bool r6 = !g_resolved.camSetter
                        ? false
                        : pl::memory::hook(reinterpret_cast<void*>(g_resolved.camSetter),
                                           reinterpret_cast<void*>(camSetterDetour),
                                           reinterpret_cast<void**>(&g_origSetter)) == 0;
    flog(std::string("hooks install: UpdatePlayerFromCamera=") + (r2 ? "ok" : "FAILED") +
         " LocalPlayerApplyTurnDelta=" + (r3 ? "ok" : "FAILED") +
         " RenderLevel=" + (g_resolved.renderLevel ? (r4 ? "ok" : "FAILED") : "skipped (sig missing)") +
         " CamTick=" + (g_resolved.camTick ? (r5 ? "ok" : "FAILED") : "MISSING (sig not found)") +
         " CamSetter=" + (g_resolved.camSetter ? (r6 ? "ok" : "FAILED") : "MISSING (sig not found)"));
    flog(std::string("sig UpdatePlayerFromCamera: ") + std::string(kSigUpdatePlayerFromCamera));
    flog(std::string("sig LocalPlayerApplyTurnDelta: ") + std::string(kSigLocalPlayerApplyTurnDelta));
    flog(std::string("sig RenderLevel: ") + std::string(kSigRenderLevel));
    flog(std::string("sig CamTick: ") + std::string(kSigCamTick));
    flog(std::string("sig CamSetter: ") + std::string(kSigCamSetter));
    g_hooksInstalled.store(r2 && r3);
    if (r2 && r3) crash::setStage("ready");
    if (!r5) flog("WARNING: CamTick hook missing/failed - the camera WILL NOT move on-device (CamSetter alone covers only packet poses)");
    if (!r4) flog("WARNING: RenderLevel diagnostic hook missing (pose drive unaffected)");
    return;
  }
}

}  // namespace

void bridgeInit(const std::string& dataDir) {
  if (g_started.exchange(true)) return;
  // kurumi (external storage) first, data dir as fallback
  for (const std::string& p : {std::string(kKurumiLog), dataDir + "/" + kLogName}) {
    int fd = ::open(p.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
      g_log.fd = fd;
      g_log.path = p;
      break;
    }
  }
  crash::setFd(g_log.fd);
  crash::install();
  LibMap lib;
  if (findLib(lib)) crash::setLib(lib.base, lib.size);
  flog(std::string("---- Screen POV v1.4.0 start  log: ") + g_log.path);
  crash::setStage("load");
  g_stop.store(false);
  g_thread = std::thread(&watchLoop);
}

void bridgeShutdown() {
  g_stop.store(true);
  if (g_thread.joinable()) g_thread.join();
  unhookAll();
  flog("bridge shutdown");
  if (g_log.fd >= 0) { ::fsync(g_log.fd); ::close(g_log.fd); g_log.fd = -1; }
}

void setOverrideEnabled(bool on) {
  g_override.store(on, std::memory_order_release);
  flog(std::string("override ") + (on ? "ON" : "OFF"));
}

bool overrideEnabled() { return g_override.load(std::memory_order_acquire); }

void setRequest(const PovRequest& req) {
  std::lock_guard<std::mutex> lk(g_mu);
  g_req = req;
}

PovRequest request() {
  std::lock_guard<std::mutex> lk(g_mu);
  return g_req;
}

bool readPlayerEye(Pose& out) { return readPlayerEyeLocked(out); }
bool playerLinked() { return g_player.load(std::memory_order_acquire) != nullptr; }
bool hooksReady() { return g_hooksInstalled.load(std::memory_order_acquire); }

void logDiagSnapshot() {
  diagBeat("snapshot");
}

}  // namespace pov
