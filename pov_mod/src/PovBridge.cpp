// PovBridge.cpp - implementation of the Screen POV mod.
//
// v1.7.0 (POSITION OVERRIDE, 2026-11-13) - the first build that actually moves the camera,
// built on v1.6.0 OBSERVER device data (see docs/freelook-re.md Phase 11):
//   * CamTick (fn_0xb7d4274) NEVER fires on this game build - dead hook, not installed.
//   * CamSetter (fn_0xb7c7268) is called EVERY rendered frame with `this` == the LRP
//     (pointer-identical to renderer+0x430's LevelRendererPlayer, verified on device).
//   * UpdatePlayerFromCamera is NOT hooked anymore. v1.4 skipped its original while the
//     override was ON (freelook copied) - that froze the player/camera sync (camera never
//     moved) and corrupted the movement/attack path (joystick mirrored/reversed on device).
//   * The ONLY write is 12 bytes at LRP+0x614, AFTER the camSetter original returns
//     (the last pose touch before the render pass), only while the module toggle is ON.
//   * Drift fix: relative mode composes from a disambiguated VANILLA snapshot (entry pose
//     compared against what WE wrote last frame), never from our own overwritten memory.
//     Absolute mode (relative OFF) writes the configured world XYZ verbatim.
//   * Orientation bytes are never touched; UpdatePlayerFromCamera / TurnDelta /
//     InputComponent family are never touched; RenderLevel stays a read-only pulse.
//   * Toggle OFF => fully vanilla (we write nothing). Safe by construction.
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
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using namespace pov;

using Clock = std::chrono::steady_clock;

constexpr const char* kTag = "ScreenPov";
constexpr const char* kLibName = "libminecraftpe.so";
constexpr const char* kKurumiLog =
    "/storage/emulated/0/Android/media/org.levimc.launcher/kurumi/pov_mod.log";
constexpr const char* kLogName = "pov_mod.log";

// ---- signatures (diagnostic only) -------------------------------------------
// RenderLevel -> LevelRenderer::render (same sig freelook/breadcrumbs/motionblur use).
constexpr std::string_view kSigRenderLevel =
    "? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5";
// Device-proven unique sig from the v1.4 REZ dumps (user's Zaphkiel dump; matches=1).
// CamTick (fn_0xb7d4274) is NEVER called on this game build - deliberately not hooked.
constexpr std::string_view kSigCamSetter =
    "FD 7B BE A9 F4 4F 01 A9 FD 03 00 91 F3 03 01 AA F4 03 00 AA ? ? ? 97 60 06 40 FD 61 12 40 BD 88 52 18 91 00 01 00 FD 81 1E 06 BD 60 42 41 FC 61 1E 40 BD 80 12 03 FD";

// ---- offsets (read-only diagnostics) ----------------------------------------
namespace off {
inline constexpr std::size_t LevelRenderer_mLevelRendererPlayer = 0x430;
inline constexpr std::size_t LevelRendererPlayer_mCamPos = 0x614;  // float3 camera position
}  // namespace off

// ---- file log + crash tracer ------------------------------------------------
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

// Read the LRP camera float3 through the renderer pointer. Returns false if unmapped.
bool readCamPos(void* renderer, float out[3]) {
  if (!renderer || !memMapped(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer, 8))
    return false;
  void* lrp = *reinterpret_cast<void**>(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer);
  if (!lrp || !memMapped(static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, 12)) return false;
  std::memcpy(out, static_cast<char*>(lrp) + off::LevelRendererPlayer_mCamPos, 12);
  return true;
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

// ---- game state (nothing is ever written to the game) -------------------------
std::mutex g_mu;
PovRequest g_req{};
std::atomic<bool> g_override{false};   // module toggle state (OFF => zero writes)
std::atomic<bool> g_hooksInstalled{false};
std::atomic<bool> g_started{false};
std::atomic<bool> g_stop{false};
std::thread g_thread;

// Observer state
std::atomic<unsigned long> g_seq{0};            // global call sequence across all detours
std::atomic<unsigned long> g_diagRenderLevel{0};
std::atomic<uintptr_t> g_lastLrp{0};            // LRP pointer as seen by RenderLevel (for compare)
// v1.7.0 override state (camSetter thread only)
std::atomic<unsigned long> g_writes{0};
std::atomic<unsigned long> g_setterCalls{0};
float g_vanilla[3] = {0.f, 0.f, 0.f};           // disambiguated vanilla pose snapshot
float g_lastWritten[3] = {0.f, 0.f, 0.f};       // exactly what WE wrote last time
bool  g_hasWritten = false;

void diagBeat(const char* tag) {
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  char b[288];
  std::snprintf(b, sizeof b,
                "%s v1.7.0 OVERRIDE override=%d seq=%lu renderLevel=%lu setter=%lu writes=%lu want(pos=%.1f,%.1f,%.1f rel=%d)",
                tag, g_override.load() ? 1 : 0, g_seq.load(), g_diagRenderLevel.load(),
                g_setterCalls.load(), g_writes.load(), r.px, r.py, r.pz, r.relativePos ? 1 : 0);
  flog(b);
}

// Log a float3 with tag + global sequence number.
void logVec3(const std::string& tag, unsigned long seq, const float v[3]) {
  char b[192];
  std::snprintf(b, sizeof b, "%s seq=%lu (%.3f,%.3f,%.3f)", tag.c_str(), seq, v[0], v[1], v[2]);
  flog(b);
}

// ---- v1.7.0: CamSetter observer+override, RenderLevel read-only pulse ---------
using RenderLevelFn = void (*)(void*, void*, void*);
using CamSetterFn = void (*)(void*, void*);
RenderLevelFn g_origRenderLevel = nullptr;
CamSetterFn g_origSetter = nullptr;

bool readPosAt(void* self, float out[3]) {
  if (!self || !memMapped(static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, 12)) return false;
  std::memcpy(out, static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, 12);
  return true;
}

bool writePosAt(void* self, const float v[3]) {
  if (!self || !memMapped(static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, 12)) return false;
  std::memcpy(static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, v, 12);
  return true;
}

void renderLevelDetour(void* renderer, void* context, void* view) {
  const unsigned long seq = g_seq.fetch_add(1, std::memory_order_relaxed) + 1;
  const unsigned long n = g_diagRenderLevel.fetch_add(1, std::memory_order_relaxed) + 1;

  // Cache the LRP pointer (informational + used by beat logging). Never writes.
  uintptr_t lrpBits = 0;
  if (renderer && memMapped(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer, 8)) {
    lrpBits = reinterpret_cast<uintptr_t>(*reinterpret_cast<void**>(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer));
    g_lastLrp.store(lrpBits, std::memory_order_relaxed);
  }
  if (n == 1) {
    char b[256];
    std::snprintf(b, sizeof b, "renderLevelDetour FIRST call seq=%lu: renderer=%p context=%p view=%p lrp=0x%lx",
                  seq, renderer, context, view, static_cast<unsigned long>(lrpBits));
    flog(b);
  }
  crash::setStage("renderLevel:orig");
  if (g_origRenderLevel) g_origRenderLevel(renderer, context, view);
  if (n % 120 == 0) {  // ~every 2s at 60fps
    float cam[3] = {0, 0, 0};
    if (readPosAt(reinterpret_cast<void*>(lrpBits), cam)) logVec3("renderer camera pos (LRP+0x614)", seq, cam);
    diagBeat("beat");
  }
}

void camSetterDetour(void* self, void* pose) {
  const unsigned long seq = g_seq.fetch_add(1, std::memory_order_relaxed) + 1;
  const unsigned long n = g_setterCalls.fetch_add(1, std::memory_order_relaxed) + 1;
  if (n == 1) {
    char b[256];
    std::snprintf(b, sizeof b, "camSetterDetour FIRST call seq=%lu: this=%p arg2=%p", seq, self, pose);
    flog(b);
  }

  // 1) Read the pose BEFORE the original runs and disambiguate: if it differs from
  //    what WE wrote last time, the tick-side writer produced a fresh vanilla pose.
  float entry[3] = {0, 0, 0};
  const bool entryOk = readPosAt(self, entry);
  if (entryOk) {
    if (!g_hasWritten || std::memcmp(entry, g_lastWritten, sizeof entry) != 0) {
      g_vanilla[0] = entry[0]; g_vanilla[1] = entry[1]; g_vanilla[2] = entry[2];
    }
  }

  // 2) The vanilla pose funnel runs as usual.
  crash::setStage("camSetter:original");
  if (g_origSetter) g_origSetter(self, pose);

  // 3) Override AFTER the original (last pose touch before the render pass).
  if (!g_override.load(std::memory_order_acquire)) {
    // OFF self-heal: if the tick-side writer never rewrote +0x614 (static pose),
    // our last pinned value is still sitting there. Restore the vanilla snapshot ONCE
    // - only when nothing fresher exists (entry is exactly what WE wrote).
    if (g_hasWritten && entryOk &&
        std::memcmp(entry, g_lastWritten, sizeof entry) == 0 &&
        std::memcmp(entry, g_vanilla, sizeof entry) != 0) {
      if (writePosAt(self, g_vanilla)) flog("override: OFF -> restored vanilla pose (LRP+0x614)");
    }
    g_hasWritten = false;
    return;
  }
  if (!entryOk) {
    crash::setStage("camSetter:skip-unmapped");
    return;
  }
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  float target[3];
  composeCamPos(r, g_vanilla, target);
  if (!writePosAt(self, target)) return;
  g_lastWritten[0] = target[0]; g_lastWritten[1] = target[1]; g_lastWritten[2] = target[2];
  g_hasWritten = true;
  const unsigned long w = g_writes.fetch_add(1, std::memory_order_relaxed) + 1;
  if (w == 1 || w % 600 == 0) {
    char b[288];
    std::snprintf(b, sizeof b,
                  "override: write #%lu seq=%lu vanilla=(%.3f,%.3f,%.3f) target=(%.3f,%.3f,%.3f) rel=%d",
                  w, seq, g_vanilla[0], g_vanilla[1], g_vanilla[2], target[0], target[1], target[2],
                  r.relativePos ? 1 : 0);
    flog(b);
  }
}

// ---- lifecycle ------------------------------------------------------------------
struct Resolved {
  uintptr_t renderLevel = 0;
  uintptr_t camSetter = 0;
};
Resolved g_resolved;

bool resolveSignatures() {
  const std::string lib(kLibName);
  const std::vector<std::string> patterns = {std::string(kSigRenderLevel), std::string(kSigCamSetter)};
  const auto found = pl::memory::resolveSignatures(patterns, lib.c_str());
  auto get = [&](std::string_view p) -> uintptr_t {
    const auto it = found.find(std::string(p));
    return it == found.end() ? 0 : it->second;
  };
  g_resolved.renderLevel = get(kSigRenderLevel);
  g_resolved.camSetter = get(kSigCamSetter);
  return g_resolved.camSetter != 0;
}

void unhookAll() {
  crash::setStage("unhook");
  auto un = [&](uintptr_t fn, void* detour, void** orig) {
    if (*orig && fn) {
      pl::memory::unhook(reinterpret_cast<void*>(fn), detour);
      *orig = nullptr;
    }
  };
  un(g_resolved.renderLevel, reinterpret_cast<void*>(renderLevelDetour), reinterpret_cast<void**>(&g_origRenderLevel));
  un(g_resolved.camSetter, reinterpret_cast<void*>(camSetterDetour), reinterpret_cast<void**>(&g_origSetter));
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
    const bool resolved = resolveSignatures();
    flog(std::string("sig RenderLevel:             ") + (g_resolved.renderLevel ? "RESOLVED" : "MISSING"));
    flog(std::string("sig CamSetter (fn_0xb7c7268):") + (g_resolved.camSetter ? "RESOLVED" : "MISSING"));
    crash::setStage("hooks");
    const bool r2 = g_resolved.renderLevel &&
        pl::memory::hook(reinterpret_cast<void*>(g_resolved.renderLevel),
                         reinterpret_cast<void*>(renderLevelDetour),
                         reinterpret_cast<void**>(&g_origRenderLevel)) == 0;
    const bool r4 = g_resolved.camSetter &&
        pl::memory::hook(reinterpret_cast<void*>(g_resolved.camSetter),
                         reinterpret_cast<void*>(camSetterDetour),
                         reinterpret_cast<void**>(&g_origSetter)) == 0;
    flog(std::string("hooks install: RenderLevel(read-only pulse)=") + (r2 ? "ok" : "FAILED"));
    flog(std::string("hooks install: CamSetter(override)=") + (r4 ? "ok" : "FAILED"));
    if (!resolved) {
      flog("CRITICAL: CamSetter sig did not resolve - the POV override is INACTIVE this run (game stays vanilla). Do not ship relying on this install; check game version.");
    }
    g_hooksInstalled.store(r4);
    crash::setStage(r4 ? "ready" : "ready-no-override");
    return;
  }
}

namespace pov {

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
  flog(std::string("---- Screen POV v1.7.0 start  log: ") + g_log.path);
  flog("POSITION OVERRIDE build. Camera override applies ONLY while the module toggle is ON: it writes 12 bytes at LRP+0x614 after the camSetter original (orientation/movement/input untouched). Toggle OFF => fully vanilla. X/Y/Z only.");
  crash::setStage("load");
  g_stop.store(false);
  g_thread = std::thread(&watchLoop);
}

void bridgeShutdown() {
  g_stop.store(true);
  if (g_thread.joinable()) g_thread.join();
  unhookAll();
  diagBeat("shutdown");
  if (g_log.fd >= 0) { ::fsync(g_log.fd); ::close(g_log.fd); g_log.fd = -1; }
}

void setOverrideEnabled(bool on) {
  g_override.store(on, std::memory_order_release);
  flog(std::string("module toggle ") + (on ? "ON" : "OFF") + " (v1.7.0: LRP+0x614 override on/off)");
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

bool hooksReady() { return g_hooksInstalled.load(std::memory_order_acquire); }

void logDiagSnapshot() {
  diagBeat("snapshot");
}

}  // namespace pov
