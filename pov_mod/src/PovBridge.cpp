// PovBridge.cpp - implementation of the Screen POV mod.
//
// v1.5.0 (SAFE BUILD, 2026-11-13) after the v1.4 on-device failure:
//   v1.4 overwrote a float3 at `this+0x614` inside two hooked functions
//   (fn_0xb7d4274 / fn_0xb7c7268) that were MISIDENTIFIED as the renderer's
//   camera-pose writer. On device: the camera never moved and the player
//   movement/attack controls were corrupted (joystick axes mirrored/reversed).
//   => This build performs NO writes to game memory - ever.
//   The only hook installed is the RenderLevel diagnostic: it READS the live
//   camera float3 (renderer+0x430 -> LevelRendererPlayer+0x614, confirmed
//   readable & correct on device) every ~120 frames and logs it. It is a
//   100% pass-through wrapper: the original is always called, nothing is saved,
//   nothing is restored, nothing is modified.
//   The camera override is DISABLED until the real pose consumer is found and
//   verified on device. Do not add writes here without a verified target.
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
// RenderLevel -> LevelRenderer::render (same 60.4 sig Freelook uses). Read-only hook.
constexpr std::string_view kSigRenderLevel =
    "? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5";

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
std::atomic<bool> g_override{false};   // module toggle state (logs only in v1.5.0)
std::atomic<bool> g_hooksInstalled{false};
std::atomic<bool> g_started{false};
std::atomic<bool> g_stop{false};
std::thread g_thread;

std::atomic<unsigned long> g_diagRenderLevel{0};

void diagBeat(const char* tag) {
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  char b[256];
  std::snprintf(b, sizeof b,
                "%s v1.5.0 SAFE override=%d renderLevel=%lu (in-game writes: NONE) want(pos=%.1f,%.1f,%.1f rel=%d)",
                tag, g_override.load() ? 1 : 0, g_diagRenderLevel.load(),
                r.px, r.py, r.pz, r.relativePos ? 1 : 0);
  flog(b);
}

// ---- the single read-only diagnostic hook --------------------------------------
using RenderLevelFn = void (*)(void*, void*, void*);
RenderLevelFn g_origRenderLevel = nullptr;

void renderLevelDetour(void* renderer, void* context, void* view) {
  g_diagRenderLevel.fetch_add(1, std::memory_order_relaxed);
  static std::atomic<bool> firstCall{false};
  if (!firstCall.exchange(true)) {
    char b[256];
    std::snprintf(b, sizeof b, "renderLevelDetour FIRST call: renderer=%p context=%p view=%p", renderer, context, view);
    flog(b);
  }
  // Diagnostic ONLY: read the camera position the renderer recorded
  // (LevelRenderer+0x430 -> LevelRendererPlayer+0x614). Never writes.
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
  crash::setStage("renderLevel:orig");
  if (g_origRenderLevel) g_origRenderLevel(renderer, context, view);
}

// ---- lifecycle ------------------------------------------------------------------
struct Resolved { uintptr_t renderLevel = 0; };
Resolved g_resolved;

bool resolveSignatures() {
  const std::string lib(kLibName);
  const std::vector<std::string> patterns = {std::string(kSigRenderLevel)};
  const auto found = pl::memory::resolveSignatures(patterns, lib.c_str());
  auto get = [&](std::string_view p) -> uintptr_t {
    const auto it = found.find(std::string(p));
    return it == found.end() ? 0 : it->second;
  };
  g_resolved.renderLevel = get(kSigRenderLevel);
  return g_resolved.renderLevel != 0;
}

void unhookAll() {
  crash::setStage("unhook");
  if (g_origRenderLevel) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.renderLevel),
                       reinterpret_cast<void*>(renderLevelDetour));
    g_origRenderLevel = nullptr;
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
    const bool resolved = resolveSignatures();
    if (!resolved) {
      flog("NOTE: RenderLevel sig not resolved - running with NO hooks at all (safest possible).");
      g_hooksInstalled.store(true);  // nothing installed, nothing to install
      crash::setStage("ready-nohooks");
      return;
    }
    crash::setStage("hooks");
    const bool r4 = pl::memory::hook(reinterpret_cast<void*>(g_resolved.renderLevel),
                                     reinterpret_cast<void*>(renderLevelDetour),
                                     reinterpret_cast<void**>(&g_origRenderLevel)) == 0;
    flog(std::string("hooks install: RenderLevel(diagnostic)=") + (r4 ? "ok (pass-through, read-only)" : "FAILED"));
    flog(std::string("sig RenderLevel: ") + std::string(kSigRenderLevel));
    g_hooksInstalled.store(true);  // diagnostic hook or not, game is untouched either way
    crash::setStage(r4 ? "ready" : "ready-nohooks");
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
  flog(std::string("---- Screen POV v1.5.0 start  log: ") + g_log.path);
  flog("SAFE MODE: camera override DISABLED (v1.4 corrupted in-game controls). The game is 100% vanilla; the X/Y/Z settings are saved but inert.");
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
  flog(std::string("module toggle ") + (on ? "ON" : "OFF") + " (v1.5.0: no camera writes - game stays 100% vanilla)");
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
