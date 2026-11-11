// PovBridge.cpp - implementation of the Screen POV camera drive.
//
// v1.2: the override pose (yaw/pitch/roll + eye offset) is injected into the CameraComponent and
//       restored around the render pass - the pattern Do-A-Barrel-Roll uses on Windows:
//         1. UpdatePlayerFromCamera  -> SKIPPED while override ON (body never syncs from the camera)
//         2. LocalPlayerApplyTurnDelta -> DROPPED while override ON (mouse does not fight the pose)
//         3. RenderLevel (LevelRenderer::render) -> BEFORE the original: find the LocalPlayer's own
//            CameraComponent via the entt registry walk (same algorithm Freelook uses to find its
//            InputComponent), snapshot it, write our desired quaternion (+ view_offset when the
//            probe confirms it). AFTER the original: restore the snapshot byte-for-byte.
//       The component layout (stride + view_offset) is discovered on-device by a one-time probe
//       (candidate strides are scored against the unit-quaternion invariant at +0x28); every step
//       is logged to pov_mod.log so a single device run tells us exactly what happened.
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
// LocalPlayerApplyTurnDelta -> the mouse/touch look delta; Freelook blocks it to keep the body
//                            from turning. We drop it entirely while the fixed POV is active so
//                            neither the body NOR the view can drift away from our pose.
// RenderLevel             -> LevelRenderer::render (BreadCrumbs/MotionBlur hook this to draw after
//                            the world / read the camera position). We wrap it: inject our pose
//                            into the CameraComponent BEFORE the original renders, restore AFTER.
constexpr std::string_view kSigUpdatePlayerFromCamera =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? F9 ? ? ? A9 ? ? ? A9 ? ? ? 91 54 D0 3B D5 ? ? ? 52";
constexpr std::string_view kSigLocalPlayerApplyTurnDelta =
    "? ? ? D1 ? ? ? FD ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 56 D0 3B D5 F3 03 00 AA F4 03 01 AA ? ? ? F9 ? ? ? F8 ? ? ? F9 ? ? ? F9";
constexpr std::string_view kSigRenderLevel =
    "? ? ? D1 ? ? ? FD ? ? ? 6D ? ? ? 6D ? ? ? 6D ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 57 D0 3B D5";

// entt type hash of MinecraftCamera::CameraComponent (matches Do-A-Barrel-Roll's static_assert;
// the hash is computed from the normalized type name, so it is the same on Android).
constexpr std::uint32_t kHashCameraComponent = 0x4f6047c7u;

// ---- offsets (Bedrock 60.4, from QYCottage/BedrockTools sdk/offsets, MIT) -------------
namespace off {
inline constexpr std::size_t Actor_mEntityContext = 0x8;      // embedded EntityContext
inline constexpr std::size_t Actor_mStateVectorComponent = 0x228;
inline constexpr std::size_t Actor_mRotationComponent = 0x238;
inline constexpr std::size_t EntityContext_mRegistry = 0x8;  // registry pointer inside context
inline constexpr std::size_t EntityContext_mEntity = 0x10;   // local player's entity id (uint32)
inline constexpr std::size_t Registry_buckets = 0x38;        // hash->pool map (entt internals)
inline constexpr std::size_t Registry_bucketsEnd = 0x40;
inline constexpr std::size_t Registry_nodes = 0x50;
inline constexpr std::size_t Registry_nodesEnd = 0x58;
inline constexpr std::size_t Pool_sparse = 0x8;              // paged entity->packed map
inline constexpr std::size_t Pool_sparseEnd = 0x10;
inline constexpr std::size_t Pool_payload = 0x50;            // paged component storage
inline constexpr std::size_t Pool_payloadEnd = 0x58;
inline constexpr std::size_t LevelRenderer_mLevelRendererPlayer = 0x430;
inline constexpr std::size_t LevelRendererPlayer_mCamPos = 0x614;
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

std::atomic<unsigned long> g_diagUpdate{0}, g_diagTurnDrop{0}, g_diagWrites{0}, g_diagRestores{0},
    g_diagRenderLevel{0}, g_diagInjectFail{0};
std::atomic<bool> g_diagUpdateFirst{false}, g_diagTurnFirst{false}, g_diagRenderFirst{false};
Clock::time_point g_diagBeat = Clock::now();

// ---- entt registry walker (same algorithm as BedrockTools' InputComponent lookup) ------
struct PoolInfo {
  uintptr_t sparse = 0, sparseEnd = 0;   // paged entity->packed map
  uintptr_t payload = 0, payloadEnd = 0; // paged component storage
};

void* registryOf(void* player) {
  if (!player) return nullptr;
  void* context = static_cast<char*>(player) + off::Actor_mEntityContext;
  if (!memMapped(static_cast<char*>(context) + off::EntityContext_mRegistry, 8)) return nullptr;
  return *reinterpret_cast<void**>(static_cast<char*>(context) + off::EntityContext_mRegistry);
}

std::uint32_t entityIdOf(void* player) {
  void* context = static_cast<char*>(player) + off::Actor_mEntityContext;
  if (!memMapped(static_cast<char*>(context) + off::EntityContext_mEntity + 4, 4)) return 0xffffffffu;
  return *reinterpret_cast<std::uint32_t*>(static_cast<char*>(context) + off::EntityContext_mEntity);
}

bool poolForHash(void* player, std::uint32_t hash, PoolInfo& out) {
  void* registry = registryOf(player);
  if (!registry) return false;
  if (!memMapped(static_cast<char*>(registry) + off::Registry_nodesEnd, 8)) return false;
  const auto buckets = *reinterpret_cast<uintptr_t*>(static_cast<char*>(registry) + off::Registry_buckets);
  const auto bucketsEnd = *reinterpret_cast<uintptr_t*>(static_cast<char*>(registry) + off::Registry_bucketsEnd);
  const auto nodes = *reinterpret_cast<uintptr_t*>(static_cast<char*>(registry) + off::Registry_nodes);
  const auto nodesEnd = *reinterpret_cast<uintptr_t*>(static_cast<char*>(registry) + off::Registry_nodesEnd);
  if (!buckets || !nodes || bucketsEnd <= buckets || nodesEnd <= nodes) return false;
  const auto count = (bucketsEnd - buckets) / sizeof(uintptr_t);
  if (!count || (count & (count - 1))) return false;
  const auto nodeCount = (nodesEnd - nodes) / 0x20;
  if (!nodeCount || nodeCount > (uintptr_t{1} << 22)) return false;
  auto index = *reinterpret_cast<uintptr_t*>(buckets + ((count - 1) & hash) * sizeof(uintptr_t));
  void* pool = nullptr;
  for (std::uintptr_t remaining = nodeCount; remaining && index < nodeCount; --remaining) {
    auto* node = reinterpret_cast<void*>(nodes + index * 0x20);
    if (!memMapped(node, 0x20)) return false;
    if (*reinterpret_cast<const std::uint32_t*>(static_cast<char*>(node) + 0x8) == hash) {
      pool = *reinterpret_cast<void**>(static_cast<char*>(node) + 0x10);
      break;
    }
    index = *reinterpret_cast<const uintptr_t*>(node);
  }
  if (!pool || !memMapped(pool, off::Pool_payloadEnd)) return false;
  out.sparse = *reinterpret_cast<uintptr_t*>(static_cast<char*>(pool) + off::Pool_sparse);
  out.sparseEnd = *reinterpret_cast<uintptr_t*>(static_cast<char*>(pool) + off::Pool_sparseEnd);
  out.payload = *reinterpret_cast<uintptr_t*>(static_cast<char*>(pool) + off::Pool_payload);
  out.payloadEnd = *reinterpret_cast<uintptr_t*>(static_cast<char*>(pool) + off::Pool_payloadEnd);
  return out.sparse && out.sparseEnd > out.sparse && out.payloadEnd > out.payload;
}

// Resolve the component slot for `entity` inside a paged entt pool. `stride` is the candidate
// sizeof(Component). Returns nullptr when the entity is not in the pool or memory is unreadable.
void* slotForEntity(const PoolInfo& pool, std::uint32_t entity, std::size_t stride, std::uint32_t* packedOut) {
  const auto sparsePage = (entity & 0x3ffffu) >> 11;
  if (sparsePage >= (pool.sparseEnd - pool.sparse) / sizeof(void*)) return nullptr;
  void** sparsePages = reinterpret_cast<void**>(pool.sparse);
  if (!memMapped(sparsePages + sparsePage, sizeof(void*))) return nullptr;
  void* page = sparsePages[sparsePage];
  if (!page) return nullptr;
  const std::size_t slot = entity & 0x7ffu;
  if (!memMapped(static_cast<char*>(page) + slot * sizeof(std::uint32_t), 4)) return nullptr;
  const auto packed = *reinterpret_cast<const std::uint32_t*>(static_cast<char*>(page) + slot * sizeof(std::uint32_t));
  if ((packed ^ (entity & 0xfffc0000u)) > 0x3fffeu) return nullptr;  // stale slot / entity not stored
  const auto payloadPage = (packed & 0x3ffffu) >> 7;
  if (payloadPage >= (pool.payloadEnd - pool.payload) / sizeof(void*)) return nullptr;
  void** payloadPages = reinterpret_cast<void**>(pool.payload);
  if (!memMapped(payloadPages + payloadPage, sizeof(void*))) return nullptr;
  void* storage = payloadPages[payloadPage];
  if (!storage) return nullptr;
  void* component = static_cast<char*>(storage) + (packed & 0x7fu) * stride;
  if (!memMapped(component, stride)) return nullptr;
  if (packedOut) *packedOut = packed;
  return component;
}

// ---- on-device probe: stride of CameraComponent + location of the local player's instance ----
enum class ProbeState : int { unprobed = 0, ok = 1, failed = 2 };

struct CameraProbe {
  ProbeState state = ProbeState::unprobed;
  std::size_t stride = 0;
  std::uint32_t packed = 0;
  void* slot = nullptr;        // last known-good slot (only for identity logging)
  void* detourCameraArg = nullptr;  // first arg seen by the UpdatePlayerFromCamera detour
  bool viewOffsetZero = false; // bytes at +0x00..+0x0c all zero -> likely view_offset
  std::array<unsigned char, 0x40> bytes{};  // hexdump source (first 0x40 bytes at probe time)
};
CameraProbe g_probe;
std::array<unsigned char, 0x100> g_snapshot{};  // component bytes before the render pass
void* g_injectedSlot = nullptr;                 // slot that received our write (for restore)
std::size_t g_injectedStride = 0;

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
  char b[448];
  std::snprintf(b, sizeof b,
                "%s override=%d update=%lu dropped=%lu renderLevel=%lu writes=%lu restores=%lu injectFail=%lu probe=%d stride=0x%zx viewOffsetZero=%d slot=%p detourArg=%p want(yaw=%.1f pitch=%.1f roll=%.1f pos=%.1f,%.1f,%.1f rel=%d)",
                tag, g_override.load() ? 1 : 0, g_diagUpdate.load(), g_diagTurnDrop.load(),
                g_diagRenderLevel.load(), g_diagWrites.load(), g_diagRestores.load(), g_diagInjectFail.load(),
                static_cast<int>(g_probe.state), g_probe.stride, g_probe.viewOffsetZero ? 1 : 0,
                g_probe.slot, g_probe.detourCameraArg,
                r.yaw, r.pitch, r.roll, r.px, r.py, r.pz, r.relativePos ? 1 : 0);
  flog(b);
}

Pose desiredPose() {
  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  Pose playerEye{};
  const bool hasPlayer = readPlayerEyeLocked(playerEye);
  return computePose(r, playerEye, hasPlayer);
}

// ---- CameraComponent probe + inject/restore ---------------------------------------------
void runCameraProbe(void* player) {
  CameraProbe& probe = g_probe;
  PoolInfo pool{};
  if (!poolForHash(player, kHashCameraComponent, pool)) {
    flog("probe: CameraComponent pool NOT FOUND in the player's registry (hash 0x4f6047c7) - "
         "the registry walk or component hash differs on this build; camera override stays OFF");
    probe.state = ProbeState::failed;
    return;
  }
  const std::uint32_t entity = entityIdOf(player);
  {
    char b[128];
    std::snprintf(b, sizeof b, "probe: player entity id = %u (0x%x)", entity, entity);
    flog(b);
  }
  if (entity == 0xffffffffu) {
    flog("probe: player entity id unreadable - camera override stays OFF");
    probe.state = ProbeState::failed;
    return;
  }
  static constexpr std::size_t kStrides[] = {0x38, 0x40, 0x44, 0x48, 0x4c, 0x50, 0x54, 0x58,
                                             0x5c, 0x60, 0x64, 0x68, 0x70, 0x78, 0x80, 0x88,
                                             0x90, 0x98, 0xa0};
  float bestScore = 1e9f;
  float bestLen = 0.f;
  std::size_t bestStride = 0;
  void* bestSlot = nullptr;
  std::uint32_t bestPacked = 0;
  for (std::size_t stride : kStrides) {
    std::uint32_t packed = 0;
    void* slot = slotForEntity(pool, entity, stride, &packed);
    if (!slot) {
      char nb[64];
      std::snprintf(nb, sizeof nb, "probe: stride 0x%zx -> entity not slot-valid", stride);
      flog(nb);
      continue;
    }
    float q[4];
    std::memcpy(q, static_cast<char*>(slot) + off::CameraComponent_mOrientation, sizeof q);
    const float len = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
    char b[192];
    std::snprintf(b, sizeof b, "probe: stride 0x%zx slot=%p quat=(%.4f,%.4f,%.4f,%.4f) len2=%.4f", stride, slot,
                  q[0], q[1], q[2], q[3], len);
    flog(b);
    if (!std::isfinite(len)) continue;
    const float score = std::fabs(len - 1.f);
    if (score < bestScore) {
      bestScore = score;
      bestLen = len;
      bestStride = stride;
      bestSlot = slot;
      bestPacked = packed;
    }
  }
  if (!bestSlot || bestScore > 0.25f) {
    flog("probe: NO stride produced a sane unit quaternion - the player entity has no usable "
         "CameraComponent here (the render camera is probably a separate entity). "
         "Camera override stays OFF. Share this log.");
    probe.state = ProbeState::failed;
    return;
  }
  probe.state = ProbeState::ok;
  probe.stride = bestStride;
  probe.slot = bestSlot;
  probe.packed = bestPacked;
  probe.bytes.fill(0);
  std::memcpy(probe.bytes.data(), bestSlot, std::min<std::size_t>(probe.bytes.size(), probe.stride));  // never read past the element
  {
    std::string hex;
    hex.reserve(0x40 * 3 + 16);
    for (std::size_t i = 0; i < probe.bytes.size(); ++i) {
      char b[4];
      std::snprintf(b, sizeof b, "%02x ", probe.bytes[i]);
      hex += b;
    }
    flog("probe: component bytes[0x00..0x40): " + hex);
  }
  const float v0 = *reinterpret_cast<const float*>(probe.bytes.data() + 0x00);
  const float v1 = *reinterpret_cast<const float*>(probe.bytes.data() + 0x04);
  const float v2 = *reinterpret_cast<const float*>(probe.bytes.data() + 0x08);
  const float v3 = *reinterpret_cast<const float*>(probe.bytes.data() + 0x0c);
  probe.viewOffsetZero = (v0 == 0.0f && v1 == 0.0f && v2 == 0.0f);
  {
    char b[256];
    std::snprintf(b, sizeof b,
                  "probe: OK stride=0x%zx packed=0x%x slot=%p quatLen2=%.4f viewOffset=(%.4f,%.4f,%.4f,%.4f) viewOffsetZero=%d",
                  bestStride, bestPacked, bestSlot, bestLen, v0, v1, v2, v3, probe.viewOffsetZero ? 1 : 0);
    flog(b);
  }
  flog(probe.viewOffsetZero
           ? "probe: +0x00..+0x0c reads as zero - treating it as view_offset; position override ENABLED"
           : "probe: +0x00..+0x0c is NOT zero - not touching it; position override stays DISABLED, orientation only");
}

// Snapshot the component, write the override pose, remember the slot for restoreCamera().
bool injectCamera(void* player) {
  CameraProbe& probe = g_probe;
  if (probe.state == ProbeState::unprobed) runCameraProbe(player);
  if (probe.state != ProbeState::ok) { g_diagInjectFail.fetch_add(1, std::memory_order_relaxed); return false; }
  PoolInfo pool{};
  if (!poolForHash(player, kHashCameraComponent, pool)) { g_diagInjectFail.fetch_add(1, std::memory_order_relaxed); return false; }
  const std::uint32_t entity = entityIdOf(player);
  std::uint32_t packed = 0;
  void* slot = slotForEntity(pool, entity, probe.stride, &packed);
  if (!slot || packed != probe.packed) {
    g_diagInjectFail.fetch_add(1, std::memory_order_relaxed);
    if (g_diagInjectFail.load() == 1) flog("inject: component slot invalid/changed - skipping write this frame");
    return false;
  }
  std::memcpy(g_snapshot.data(), slot, probe.stride);

  PovRequest r{};
  { std::lock_guard<std::mutex> lk(g_mu); r = g_req; }
  Pose p = desiredPose();
  const Quat q = quatFromYawPitchRoll(p.yaw, p.pitch, p.roll);
  std::memcpy(static_cast<char*>(slot) + off::CameraComponent_mOrientation, &q, sizeof q);

  if (probe.viewOffsetZero) {
    float off[3];
    if (r.relativePos) {
      off[0] = r.px; off[1] = r.py; off[2] = r.pz;
    } else {
      Pose eye{};
      if (readPlayerEyeLocked(eye)) {
        off[0] = p.x - eye.x; off[1] = p.y - eye.y; off[2] = p.z - eye.z;
      } else {
        off[0] = off[1] = off[2] = 0.0f;
      }
    }
    std::memcpy(static_cast<char*>(slot) + 0x00, off, sizeof off);
  }
  g_injectedSlot = slot;
  g_injectedStride = probe.stride;
  g_diagWrites.fetch_add(1, std::memory_order_relaxed);
  return true;
}

void restoreCamera() {
  void* slot = g_injectedSlot;
  const std::size_t stride = g_injectedStride ? g_injectedStride : g_probe.stride;
  g_injectedSlot = nullptr;
  if (!slot || !stride) return;
  if (!memMapped(slot, stride)) {
    flog("restore: component slot no longer mapped - cannot restore original bytes");
    return;
  }
  std::memcpy(slot, g_snapshot.data(), stride);
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
    g_probe.detourCameraArg = camera;
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
  if (g_override.load(std::memory_order_acquire)) {
    // Drop the look delta entirely: neither the body nor the view can drift off our pose.
    g_diagTurnDrop.fetch_add(1, std::memory_order_relaxed);
    if (!g_diagTurnFirst.exchange(true)) {
      flog("turnDeltaDetour: first delta DROPPED (fixed POV holds the camera)");
    }
    return;
  }
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
  void* player = g_player.load(std::memory_order_acquire);
  bool injected = false;
  if (want && player) {
    crash::setStage("renderLevel:inject");
    injected = injectCamera(player);
    crash::setStage("renderLevel:orig");
  }
  if (g_origRenderLevel) g_origRenderLevel(renderer, context, view);
  if (injected) {
    crash::setStage("renderLevel:restore");
    restoreCamera();
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
  flog(std::string("---- Screen POV v1.2.0 start  log: ") + g_log.path);
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
