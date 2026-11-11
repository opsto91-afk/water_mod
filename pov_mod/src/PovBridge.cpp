// PovBridge.cpp - implementation of the Screen POV camera drive.
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

namespace pov {
namespace {

using Clock = std::chrono::steady_clock;

constexpr const char* kTag = "ScreenPov";
constexpr const char* kLibName = "libminecraftpe.so";
constexpr const char* kKurumiLog =
    "/storage/emulated/0/Android/media/org.levimc.launcher/kurumi/pov_mod.log";
constexpr const char* kLogName = "pov_mod.log";

// ---- signatures (60.4 bytes; same strings BedrockTools ships in its table) -----------
// id 0x8c = freelook headGet (used as the per-frame camera-sync call site)
// id 0x23 = breadcrumbs module hook (used defensively as the pre-render write point)
constexpr std::string_view kSigUpdatePlayerFromCamera =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? A9 ? ? ? 91 54 D0 3B D5 ? ? ? 52";
constexpr std::string_view kSigRenderLevel =
    "? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5";

// ---- offsets (Bedrock 60.4, from QYCottage/BedrockTools sdk/offsets, MIT) -------------
namespace off {
inline constexpr std::size_t Actor_mStateVectorComponent = 0x228;
inline constexpr std::size_t Actor_mRotationComponent = 0x238;
inline constexpr std::size_t Actor_mLevel = 0x1F0;
inline constexpr std::size_t Actor_mDimension = 0x1E0;
inline constexpr std::size_t LevelRenderer_mLevelRendererPlayer = 0x430;
inline constexpr std::size_t LevelRendererPlayer_mCamPos = 0x614;  // Vec3, render-side eye pos
inline constexpr std::size_t CameraComponent_mOrientation = 0x28;  // 4 floats = quaternion
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

std::atomic<unsigned long> g_diagUpdate{0}, g_diagRender{0}, g_diagPoseW{0}, g_diagCamW{0};
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
void* actorLevel(void* player) {
  if (!player) return nullptr;
  if (!memMapped(static_cast<char*>(player) + off::Actor_mLevel, 8)) return nullptr;
  return *reinterpret_cast<void**>(static_cast<char*>(player) + off::Actor_mLevel);
}
void* actorDimension(void* player) {
  if (!player) return nullptr;
  if (!memMapped(static_cast<char*>(player) + off::Actor_mDimension, 8)) return nullptr;
  return *reinterpret_cast<void**>(static_cast<char*>(player) + off::Actor_mDimension);
}

bool playerLooksValid(void* player) {
  return player && rotationComponent(player) && stateVectorComponent(player) &&
         actorLevel(player) && actorDimension(player);
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
  char b[320];
  std::snprintf(b, sizeof b, "%s override=%d update=%lu render=%lu poseW=%lu camW=%lu want(yaw=%.1f pitch=%.1f pos=%.1f,%.1f,%.1f rel=%d)",
                tag, g_override.load() ? 1 : 0, g_diagUpdate.load(), g_diagRender.load(),
                g_diagPoseW.load(), g_diagCamW.load(), r.yaw, r.pitch, r.px, r.py, r.pz,
                r.relativePos ? 1 : 0);
  flog(b);
}

Pose desiredPose() {
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  Pose playerEye{};
  const bool hasPlayer = readPlayerEyeLocked(playerEye);
  return computePose(r, playerEye, hasPlayer);
}

// ---- frame hooks -------------------------------------------------------------------------
using UpdatePlayerFromCameraFn = void (*)(void*, void*, void*);
using RenderLevelFn = void (*)(void*, void*, void*);
UpdatePlayerFromCameraFn g_origUpdatePlayer = nullptr;
RenderLevelFn g_origRenderLevel = nullptr;

void updatePlayerDetour(void* camera, void* component, void* player) {
  g_diagUpdate.fetch_add(1, std::memory_order_relaxed);
  if (!g_diagUpdateFirst.exchange(true)) {
    char b[200];
    std::snprintf(b, sizeof b, "updatePlayerDetour FIRST call: camera=%p component=%p player=%p", camera, component, player);
    flog(b);
  }
  if (player) g_player.store(player, std::memory_order_release);
  const bool over = g_override.load(std::memory_order_acquire);
  crash::setStage("updatePlayer:original");
  if (g_origUpdatePlayer) g_origUpdatePlayer(camera, component, player);
  // freelook-style: AFTER the game synced the camera, re-apply our orientation on top.
  if (over && camera && memMapped(camera, off::CameraComponent_mOrientation + sizeof(Quat))) {
    const Pose p = desiredPose();
    const Quat q = quatFromYawPitch(p.yaw, p.pitch);
    std::memcpy(static_cast<char*>(camera) + off::CameraComponent_mOrientation, &q, sizeof q);
    g_diagPoseW.fetch_add(1, std::memory_order_relaxed);
    diagBeat("override");
  }
}

void renderLevelDetour(void* renderer, void* context, void* view) {
  g_diagRender.fetch_add(1, std::memory_order_relaxed);
  if (!g_diagRenderFirst.exchange(true)) {
    char b[200];
    std::snprintf(b, sizeof b, "renderLevelDetour FIRST call: renderer=%p view=%p", renderer, view);
    flog(b);
  }
  void* playerRenderer = nullptr;
  if (renderer) {
    if (memMapped(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer, 8)) {
      playerRenderer = *reinterpret_cast<void**>(static_cast<char*>(renderer) + off::LevelRenderer_mLevelRendererPlayer);
    }
  }
  const bool over = g_override.load(std::memory_order_acquire);
  if (over && playerRenderer) {
    void* at = static_cast<char*>(playerRenderer) + off::LevelRendererPlayer_mCamPos;
    if (memMapped(at, sizeof(float) * 3)) {
      const Pose p = desiredPose();
      const float eye[3] = {p.x, p.y, p.z};
      std::memcpy(at, eye, sizeof eye);
      g_diagCamW.fetch_add(1, std::memory_order_relaxed);
    }
  }
  crash::setStage("renderLevel:original");
  if (g_origRenderLevel) g_origRenderLevel(renderer, context, view);
}

// ---- lifecycle ---------------------------------------------------------------------------
struct Resolved { uintptr_t updatePlayerFromCamera = 0, renderLevel = 0; };
Resolved g_resolved;

bool resolveSignatures() {
  const std::string lib(kLibName);
  const std::vector<std::string> patterns = {std::string(kSigUpdatePlayerFromCamera), std::string(kSigRenderLevel)};
  const auto found = pl::memory::resolveSignatures(patterns, lib.c_str());
  auto get = [&](std::string_view p) -> uintptr_t {
    const auto it = found.find(std::string(p));
    return it == found.end() ? 0 : it->second;
  };
  g_resolved.updatePlayerFromCamera = get(kSigUpdatePlayerFromCamera);
  g_resolved.renderLevel = get(kSigRenderLevel);
  return g_resolved.updatePlayerFromCamera != 0 && g_resolved.renderLevel != 0;
}

void unhookAll() {
  crash::setStage("unhook");
  if (g_origUpdatePlayer) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.updatePlayerFromCamera), reinterpret_cast<void*>(updatePlayerDetour));
    g_origUpdatePlayer = nullptr;
  }
  if (g_origRenderLevel) {
    pl::memory::unhook(reinterpret_cast<void*>(g_resolved.renderLevel), reinterpret_cast<void*>(renderLevelDetour));
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
    if (!resolveSignatures()) {
      flog("SIGNATURE RESOLUTION FAILED - Screen POV stays INACTIVE (check the log for the missing one)");
      if (!g_resolved.updatePlayerFromCamera) flog("  missing: UpdatePlayerFromCamera");
      if (!g_resolved.renderLevel) flog("  missing: RenderLevel");
      return;
    }
    crash::setStage("hooks");
    const bool r2 = pl::memory::hook(reinterpret_cast<void*>(g_resolved.updatePlayerFromCamera),
                                     reinterpret_cast<void*>(updatePlayerDetour),
                                     reinterpret_cast<void**>(&g_origUpdatePlayer)) == 0;
    const bool r3 = pl::memory::hook(reinterpret_cast<void*>(g_resolved.renderLevel),
                                     reinterpret_cast<void*>(renderLevelDetour),
                                     reinterpret_cast<void**>(&g_origRenderLevel)) == 0;
    flog(std::string("hooks install: UpdatePlayerFromCamera=") + (r2 ? "ok" : "FAILED") +
         " RenderLevel=" + (r3 ? "ok" : "FAILED"));
    g_hooksInstalled.store(r2 && r3);
    if (r2 && r3) crash::setStage("ready");
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
  flog(std::string("---- Screen POV v1.0.0 start  log: ") + g_log.path);
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
