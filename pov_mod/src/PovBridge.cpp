// PovBridge.cpp - implementation of the Screen POV mod.
//
// v1.6.0 (OBSERVER BUILD, 2026-11-13) - ZERO writes to game memory, ever.
//   v1.5.0 proved the mod can run with a diagnostic pass-through hook safely.
//   v1.4 proved we still don't know the true frame order / which function writes
//   the renderer camera pose (LRP+0x614). This build answers it with LOGS ONLY:
//     * UpdatePlayerFromCamera - pass-through; logs pointers + a global sequence no.
//     * RenderLevel            - pass-through; logs renderer LRP pointer + LRP+0x614
//                                float3 BEFORE and AFTER the original runs (first 48 frames).
//     * CamTick (fn_0xb7d4274) - pass-through; logs `this`, compares it against the LRP
//                                seen by RenderLevel, and logs this+0x614 pre/post original
//                                (first 48 calls). Answers: is CamTick's `this` the LRP?
//     * CamSetter (fn_0xb7c7268)- pass-through; same treatment (first 48 calls).
//   Every hook ALWAYS calls the original. Nothing is saved, restored, overwritten or skipped.
//   A global sequence counter is printed with every line so the exact call interleaving
//   (tick vs render vs updatePlayer) can be reconstructed from kurumi/pov_mod.log.
//   The camera override remains DISABLED until the pose writer is proven on device.
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
// UpdatePlayerFromCamera -> BedrockTools freelook sig (60.4). Same string pov v1.4 used.
constexpr std::string_view kSigUpdatePlayerFromCamera =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? A9 ? ? ? 91 54 D0 3B D5 ? ? ? 52";
// RenderLevel -> LevelRenderer::render (same sig freelook/breadcrumbs/motionblur use).
constexpr std::string_view kSigRenderLevel =
    "? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5";
// Device-proven unique sigs from the v1.4 REZ dumps (user's Zaphkiel dump; matches=1):
constexpr std::string_view kSigCamTick =
    "FF C3 06 D1 EA A3 00 FD E9 23 15 6D FD 7B 16 A9 FC 67 17 A9 F8 5F 18 A9 F6 57 19 A9 F4 4F 1A A9 FD 83 05 91 57 D0 3B D5 ? ? ? F0 94 02 02 91 E8 ? ? F9 F3 03 00 AA";
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
std::atomic<bool> g_override{false};   // module toggle state (logs only in v1.6.0)
std::atomic<bool> g_hooksInstalled{false};
std::atomic<bool> g_started{false};
std::atomic<bool> g_stop{false};
std::thread g_thread;

// Observer state
std::atomic<unsigned long> g_seq{0};            // global call sequence across all detours
std::atomic<unsigned long> g_diagUpdate{0};     // UpdatePlayerFromCamera calls
std::atomic<unsigned long> g_diagRenderLevel{0};
std::atomic<unsigned long> g_diagTick{0};
std::atomic<unsigned long> g_diagSetter{0};
std::atomic<uintptr_t> g_lastLrp{0};            // LRP pointer as seen by RenderLevel (for compare)

void diagBeat(const char* tag) {
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  char b[288];
  std::snprintf(b, sizeof b,
                "%s v1.6.0 OBSERVER override=%d seq=%lu update=%lu renderLevel=%lu tick=%lu setter=%lu (in-game writes: NONE) want(pos=%.1f,%.1f,%.1f rel=%d)",
                tag, g_override.load() ? 1 : 0, g_seq.load(), g_diagUpdate.load(), g_diagRenderLevel.load(),
                g_diagTick.load(), g_diagSetter.load(), r.px, r.py, r.pz, r.relativePos ? 1 : 0);
  flog(b);
}

// Log a float3 with tag + global sequence number.
void logVec3(const std::string& tag, unsigned long seq, const float v[3]) {
  char b[192];
  std::snprintf(b, sizeof b, "%s seq=%lu (%.3f,%.3f,%.3f)", tag.c_str(), seq, v[0], v[1], v[2]);
  flog(b);
}

// ---- pass-through diagnostic hooks (ALL call the original, NONE write) --------
using UpdatePlayerFromCameraFn = void (*)(void*, void*, void*);
using RenderLevelFn = void (*)(void*, void*, void*);
using CamFn = void (*)(void*, void*);  // CamTick / CamSetter: (this, arg2)
UpdatePlayerFromCameraFn g_origUpdatePlayer = nullptr;
RenderLevelFn g_origRenderLevel = nullptr;
CamFn g_origTick = nullptr;
CamFn g_origSetter = nullptr;

void updatePlayerDetour(void* camera, void* component, void* player) {
  const unsigned long seq = g_seq.fetch_add(1, std::memory_order_relaxed) + 1;
  const unsigned long n = g_diagUpdate.fetch_add(1, std::memory_order_relaxed) + 1;
  if (n == 1) {
    char b[256];
    std::snprintf(b, sizeof b, "updatePlayerDetour FIRST call seq=%lu: camera=%p component=%p player=%p",
                  seq, camera, component, player);
    flog(b);
  } else if (n <= 24) {
    char b[128];
    std::snprintf(b, sizeof b, "updatePlayer seq=%lu player=%p", seq, player);
    flog(b);
  }
  crash::setStage("updatePlayer:original");
  if (g_origUpdatePlayer) g_origUpdatePlayer(camera, component, player);
}

void renderLevelDetour(void* renderer, void* context, void* view) {
  const unsigned long seq = g_seq.fetch_add(1, std::memory_order_relaxed) + 1;
  const unsigned long n = g_diagRenderLevel.fetch_add(1, std::memory_order_relaxed) + 1;

  // Cache the LRP pointer so the tick/setter detours can compare `this == LRP`.
  uintptr_t lrpBits = 0;
  if (renderer && memMapped(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer, 8)) {
    lrpBits = reinterpret_cast<uintptr_t>(*reinterpret_cast<void**>(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer));
    g_lastLrp.store(lrpBits, std::memory_order_relaxed);
  }

  float pre[3] = {0, 0, 0};
  const bool preOk = readCamPos(renderer, pre);

  if (n == 1) {
    char b[256];
    std::snprintf(b, sizeof b, "renderLevelDetour FIRST call seq=%lu: renderer=%p context=%p view=%p lrp=0x%lx",
                  seq, renderer, context, view, static_cast<unsigned long>(lrpBits));
    flog(b);
  }
  const bool detail = n <= 48;
  if (detail && preOk) logVec3("renderLevel PRE  ", seq, pre);

  crash::setStage("renderLevel:orig");
  if (g_origRenderLevel) g_origRenderLevel(renderer, context, view);

  if (detail) {
    float post[3] = {0, 0, 0};
    if (readCamPos(renderer, post)) {
      logVec3("renderLevel POST ", seq, post);
      if (preOk) {
        const bool changed = std::memcmp(pre, post, sizeof pre) != 0;
        char b[96];
        std::snprintf(b, sizeof b, "renderLevel seq=%lu LRP+0x614 changed-by-original=%d", seq, changed ? 1 : 0);
        flog(b);
      }
    }
  }

  if (n % 120 == 0) {  // ~every 2s at 60fps
    float cam[3] = {0, 0, 0};
    if (readCamPos(renderer, cam)) logVec3("renderer camera pos (LRP+0x614)", seq, cam);
    diagBeat("beat");
  }
}

// CamTick / CamSetter shared observer logic. Never writes; always calls original.
void camDetourCommon(const char* tag, void* self, void* arg2, CamFn orig,
                     std::atomic<unsigned long>& counter, std::atomic<unsigned long>& seqCtr) {
  const unsigned long seq = seqCtr.fetch_add(1, std::memory_order_relaxed) + 1;
  const unsigned long n = counter.fetch_add(1, std::memory_order_relaxed) + 1;
  const bool detail = n <= 48;
  const uintptr_t lastLrp = g_lastLrp.load(std::memory_order_relaxed);
  const int thisIsLrp = (lastLrp != 0 && reinterpret_cast<uintptr_t>(self) == lastLrp) ? 1 : 0;

  if (n == 1) {
    char b[288];
    std::snprintf(b, sizeof b,
                  "%s FIRST call seq=%lu: this=%p arg2=%p lastRenderLevelLRP=0x%lx this==LRP=%d",
                  tag, seq, self, arg2, static_cast<unsigned long>(lastLrp), thisIsLrp);
    flog(b);
  }

  float pre[3] = {0, 0, 0};
  const bool preOk =
      self && memMapped(static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, 12) &&
      (std::memcpy(pre, static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, sizeof pre), true);

  crash::setStage(std::strcmp(tag, "camTick") == 0 ? "camTick:original" : "camSetter:original");
  if (orig) orig(self, arg2);

  if (detail) {
    if (preOk) logVec3(std::string(tag) + std::string(" PRE "), seq, pre);
    float post[3] = {0, 0, 0};
    const bool postOk =
        self && memMapped(static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, 12) &&
        (std::memcpy(post, static_cast<char*>(self) + off::LevelRendererPlayer_mCamPos, sizeof post), true);
    if (postOk) {
      logVec3(std::string(tag) + std::string(" POST"), seq, post);
      char b[128];
      std::snprintf(b, sizeof b,
                    "%s seq=%lu this==LRP=%d this+0x614 changed-by-original=%d",
                    tag, seq, thisIsLrp, preOk && std::memcmp(pre, post, sizeof pre) != 0 ? 1 : 0);
      flog(b);
    }
  }
}

void camTickDetour(void* self, void* flagHolder) {
  camDetourCommon("camTick", self, flagHolder, g_origTick, g_diagTick, g_seq);
}

void camSetterDetour(void* self, void* pose) {
  camDetourCommon("camSetter", self, pose, g_origSetter, g_diagSetter, g_seq);
}

// ---- lifecycle ------------------------------------------------------------------
struct Resolved {
  uintptr_t renderLevel = 0;
  uintptr_t updatePlayer = 0;
  uintptr_t camTick = 0;
  uintptr_t camSetter = 0;
};
Resolved g_resolved;

bool resolveSignatures() {
  const std::string lib(kLibName);
  const std::vector<std::string> patterns = {
      std::string(kSigUpdatePlayerFromCamera), std::string(kSigRenderLevel),
      std::string(kSigCamTick), std::string(kSigCamSetter)};
  const auto found = pl::memory::resolveSignatures(patterns, lib.c_str());
  auto get = [&](std::string_view p) -> uintptr_t {
    const auto it = found.find(std::string(p));
    return it == found.end() ? 0 : it->second;
  };
  g_resolved.updatePlayer = get(kSigUpdatePlayerFromCamera);
  g_resolved.renderLevel = get(kSigRenderLevel);
  g_resolved.camTick = get(kSigCamTick);
  g_resolved.camSetter = get(kSigCamSetter);
  return true;  // every hook is independent & optional
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
  un(g_resolved.updatePlayer, reinterpret_cast<void*>(updatePlayerDetour), reinterpret_cast<void**>(&g_origUpdatePlayer));
  un(g_resolved.camTick, reinterpret_cast<void*>(camTickDetour), reinterpret_cast<void**>(&g_origTick));
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
    resolveSignatures();
    flog(std::string("sig UpdatePlayerFromCamera: ") + (g_resolved.updatePlayer ? "RESOLVED" : "MISSING"));
    flog(std::string("sig RenderLevel:             ") + (g_resolved.renderLevel ? "RESOLVED" : "MISSING"));
    flog(std::string("sig CamTick (fn_0xb7d4274):  ") + (g_resolved.camTick ? "RESOLVED" : "MISSING"));
    flog(std::string("sig CamSetter (fn_0xb7c7268):") + (g_resolved.camSetter ? "RESOLVED" : "MISSING"));
    crash::setStage("hooks");
    const bool r1 = g_resolved.updatePlayer &&
        pl::memory::hook(reinterpret_cast<void*>(g_resolved.updatePlayer),
                         reinterpret_cast<void*>(updatePlayerDetour),
                         reinterpret_cast<void**>(&g_origUpdatePlayer)) == 0;
    const bool r2 = g_resolved.renderLevel &&
        pl::memory::hook(reinterpret_cast<void*>(g_resolved.renderLevel),
                         reinterpret_cast<void*>(renderLevelDetour),
                         reinterpret_cast<void**>(&g_origRenderLevel)) == 0;
    const bool r3 = g_resolved.camTick &&
        pl::memory::hook(reinterpret_cast<void*>(g_resolved.camTick),
                         reinterpret_cast<void*>(camTickDetour),
                         reinterpret_cast<void**>(&g_origTick)) == 0;
    const bool r4 = g_resolved.camSetter &&
        pl::memory::hook(reinterpret_cast<void*>(g_resolved.camSetter),
                         reinterpret_cast<void*>(camSetterDetour),
                         reinterpret_cast<void**>(&g_origSetter)) == 0;
    flog(std::string("hooks install: UpdatePlayerFromCamera(pass-through)=") + (r1 ? "ok" : "FAILED"));
    flog(std::string("hooks install: RenderLevel(pass-through)=") + (r2 ? "ok" : "FAILED"));
    flog(std::string("hooks install: CamTick(pass-through)=") + (r3 ? "ok" : "FAILED"));
    flog(std::string("hooks install: CamSetter(pass-through)=") + (r4 ? "ok" : "FAILED"));
    g_hooksInstalled.store(true);  // all hooks are read-only observers; game is untouched either way
    crash::setStage("ready");
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
  flog(std::string("---- Screen POV v1.6.0 start  log: ") + g_log.path);
  flog("OBSERVER MODE: camera override DISABLED (v1.4 corrupted in-game controls). The game is 100% vanilla; the X/Y/Z settings are saved but inert. This build only LOGS frame order + pointers + LRP+0x614; it never writes game memory.");
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
  flog(std::string("module toggle ") + (on ? "ON" : "OFF") + " (v1.6.0 OBSERVER: no camera writes - game stays 100% vanilla)");
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
