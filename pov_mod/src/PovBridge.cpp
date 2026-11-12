// PovBridge.cpp - implementation of the Screen POV camera drive.
//
// v1.3 (2026-11-12): XYZ-position override ONLY, driven straight into the renderer's own camera
//       record - no entt, no CameraComponent, no signatures beyond 60.4's RenderLevel.
//         1. UpdatePlayerFromCamera  -> SKIPPED while override ON (body never syncs from the camera)
//         2. LocalPlayerApplyTurnDelta -> PASS-THROUGH while override ON (normal look works)
//         3. RenderLevel (LevelRenderer::render) -> BEFORE the original: resolve
//            renderer + 0x430 (LevelRenderer::mLevelRendererPlayer) -> LevelRendererPlayer,
//            snapshot the float3 camera position at +0x614, write our XYZ over it
//            (relative mode: live position + offset; fixed mode: absolute world position).
//            AFTER the original: restore the 12 bytes.
//       v1.2 failed on device because the CameraComponent entt hash (0x4f6047c7, from the
//       Windows DABR build) matches nothing on Android (custom entt hash); the log showed
//       writes=0 on every frame. The LRP+0x614 read path was verified live on device.
//       Every step is logged to pov_mod.log.
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
// LocalPlayerApplyTurnDelta -> the mouse/touch look delta. As of v1.3 it is a PASS-THROUGH
//                            (the view is free; only the position is pinned). It stays hooked
//                            so toggling back is cheap and the install log keeps its shape.
// RenderLevel             -> LevelRenderer::render (BreadCrumbs/MotionBlur hook this too). v1.3:
//                            inject the camera POSITION (float3 at LRP+0x614) before the original
//                            renders and restore it after.
constexpr std::string_view kSigUpdatePlayerFromCamera =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? A9 ? ? ? 91 54 D0 3B D5 ? ? ? 52";
constexpr std::string_view kSigLocalPlayerApplyTurnDelta =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 56 D0 3B D5 F3 03 00 AA F4 03 01 AA ? ? ? F9 ? ? ? F8 ? ? ? F9 ? ? ? F9";
constexpr std::string_view kSigRenderLevel =
    "? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5";

// v1.3: the CameraComponent entt hash is gone - the override writes the renderer's own
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

std::atomic<unsigned long> g_diagUpdate{0}, g_diagWrites{0}, g_diagRestores{0},
    g_diagRenderLevel{0}, g_diagInjectFail{0};
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
                "%s override=%d update=%lu renderLevel=%lu injects=%lu restores=%lu injectFail=%lu want(pos=%.1f,%.1f,%.1f rel=%d)",
                tag, g_override.load() ? 1 : 0, g_diagUpdate.load(), g_diagRenderLevel.load(),
                g_diagWrites.load(), g_diagRestores.load(), g_diagInjectFail.load(),
                r.px, r.py, r.pz, r.relativePos ? 1 : 0);
  flog(b);
}

// ---- direct LevelRendererPlayer position override (v1.3) ----------------------------------
// The renderer keeps the live camera position as a float3 at LevelRendererPlayer + 0x614
// (verified live on device: "renderer camera pos (LRP+0x614)" tracks the world camera every
// frame). v1.2's entt/CameraComponent path found nothing on Android (writes=0, probe failed
// every frame), so the override is written straight into the renderer's own struct:
//   BEFORE orig render: snapshot the 12 bytes, write our XYZ (relative: live pos + offset,
//                       fixed: absolute world position);
//   AFTER  orig render: restore the snapshot byte-for-byte.
std::array<unsigned char, 12> g_posSnapshot{};  // original camera position bytes
void* g_injectedLrp = nullptr;                  // LRP that received our write (for restore)

bool injectCameraPos(void* renderer) {
  if (!renderer || !memMapped(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer, 8)) {
    g_diagInjectFail.fetch_add(1, std::memory_order_relaxed);
    return false;
  }
  void* lrp = *reinterpret_cast<void**>(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer);
  if (!lrp || !memMapped(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, 12)) {
    g_diagInjectFail.fetch_add(1, std::memory_order_relaxed);
    return false;
  }
  float cur[3];
  std::memcpy(cur, static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, sizeof cur);
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  float target[3];
  if (r.relativePos) {
    target[0] = cur[0] + r.px;
    target[1] = cur[1] + r.py;
    target[2] = cur[2] + r.pz;
  } else {
    target[0] = r.px;
    target[1] = r.py;
    target[2] = r.pz;
  }
  std::memcpy(g_posSnapshot.data(), cur, sizeof cur);
  std::memcpy(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, target, sizeof target);
  g_injectedLrp = lrp;
  g_diagWrites.fetch_add(1, std::memory_order_relaxed);
  static std::atomic<bool> firstInjectLog{false};
  if (!firstInjectLog.exchange(true)) {
    char b[256];
    std::snprintf(b, sizeof b,
                  "inject: FIRST direct LRP pos write cur=(%.3f,%.3f,%.3f) target=(%.3f,%.3f,%.3f) rel=%d",
                  cur[0], cur[1], cur[2], target[0], target[1], target[2], r.relativePos ? 1 : 0);
    flog(b);
  }
  return true;
}

void restoreCameraPos() {
  void* lrp = g_injectedLrp;
  g_injectedLrp = nullptr;
  if (!lrp) return;
  if (!memMapped(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, 12)) {
    flog("restore: LRP no longer mapped - cannot restore original camera pos");
    return;
  }
  std::memcpy(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, g_posSnapshot.data(), 12);
  g_diagRestores.fetch_add(1, std::memory_order_relaxed);
}

// ---- frame hooks -------------------------------------------------------------------------
using UpdatePlayerFromCameraFn = void (*)(void*, void*, void*);
using TurnDeltaFn = void (*)(void*, void*);
using RenderLevelFn = void (*)(void*, void*, void*);
UpdatePlayerFromCameraFn g_origUpdatePlayer = nullptr;
TurnDeltaFn g_origTurnDelta = nullptr;
RenderLevelFn g_origRenderLevel = nullptr;

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
  // v1.3: pass-through. The view is free (look works normally); only the camera position
  // is pinned at render time. The hook stays installed so toggling is cheap and the
  // install log keeps its shape.
  crash::setStage("turnDelta:original");
  if (g_origTurnDelta) g_origTurnDelta(player, delta);
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

  const bool want = g_override.load(std::memory_order_acquire);
  bool injected = false;
  if (want) {
    crash::setStage("renderLevel:inject");
    injected = injectCameraPos(renderer);
    crash::setStage("renderLevel:orig");
  }
  if (g_origRenderLevel) g_origRenderLevel(renderer, context, view);
  if (injected) {
    crash::setStage("renderLevel:restore");
    restoreCameraPos();
  }
}

// ---- lifecycle ---------------------------------------------------------------------------
struct Resolved { uintptr_t updatePlayerFromCamera = 0, localPlayerApplyTurnDelta = 0, renderLevel = 0; };
Resolved g_resolved;

bool resolveSignatures() {
  const std::string lib(kLibName);
  const std::vector<std::string> patterns = {std::string(kSigUpdatePlayerFromCamera),
                                             std::string(kSigLocalPlayerApplyTurnDelta),
                                             std::string(kSigRenderLevel)};
  const auto found = pl::memory::resolveSignatures(patterns, lib.c_str());
  auto get = [&](std::string_view p) -> uintptr_t {
    const auto it = found.find(std::string(p));
    return it == found.end() ? 0 : it->second;
  };
  g_resolved.updatePlayerFromCamera = get(kSigUpdatePlayerFromCamera);
  g_resolved.localPlayerApplyTurnDelta = get(kSigLocalPlayerApplyTurnDelta);
  g_resolved.renderLevel = get(kSigRenderLevel);
  return g_resolved.updatePlayerFromCamera != 0 && g_resolved.localPlayerApplyTurnDelta != 0;
}

void unhookAll() {
  crash::setStage("unhook");
  if (g_origRenderLevel) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.renderLevel), reinterpret_cast<void*>(renderLevelDetour));
    g_origRenderLevel = nullptr;
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
    if (!g_resolved.renderLevel) flog("  missing: RenderLevel (degraded: pose pin only, no camera drive)");
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
    flog(std::string("hooks install: UpdatePlayerFromCamera=") + (r2 ? "ok" : "FAILED") +
         " LocalPlayerApplyTurnDelta=" + (r3 ? "ok" : "FAILED") +
         " RenderLevel=" + (g_resolved.renderLevel ? (r4 ? "ok" : "FAILED") : "skipped (sig missing)"));
    flog(std::string("sig UpdatePlayerFromCamera: ") + std::string(kSigUpdatePlayerFromCamera));
    flog(std::string("sig LocalPlayerApplyTurnDelta: ") + std::string(kSigLocalPlayerApplyTurnDelta));
    flog(std::string("sig RenderLevel: ") + std::string(kSigRenderLevel));
    g_hooksInstalled.store(r2 && r3);
    if (r2 && r3) crash::setStage("ready");
    if (!r4) flog("WARNING: RenderLevel hook missing - the view will stay frozen like v1.1 (no pose drive)");
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
  flog(std::string("---- Screen POV v1.3.0 start  log: ") + g_log.path);
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
