# FIXES.md — what was broken in the handoff zip and what was changed

The handoff archive (`v31`) did not compile. These are the exact problems and fixes.

## 1. `third_party/imgui/imstb_rectpack.h` was an empty placeholder (494 bytes)
`imgui_draw.cpp` uses `stbrp_rect`, `stbrp_context`, `stbrp_init_target`, `stbrp_pack_rects`
from this header, so ImGui itself failed to compile.
**Fix:** replaced with the real `imstb_rectpack.h` from ImGui (v1.88, stb_rect_pack 1.01).

## 2. `src/MyMod_FIXED.cpp` was a half-written file
48 lines, ends in the middle of `CinematicMod::enable()` (`getSelf` cut off), and it
referenced a `CinematicMod` class that does not exist in it. It was never in `CMakeLists.txt`.
**Fix:** deleted. `src/MyMod.cpp` is the single entry point.

## 3. `src/MyMod.cpp` used an API that does not exist in preloader-android 0.2.2
| Used in the zip | Reality in SDK 0.2.2 |
|---|---|
| `extern "C" createMod()` | the preloader looks up `PLGetModRegistration` → use `PL_REGISTER_MOD(TYPE, INSTANCE)` |
| `pl::input::registerTextInputCallback(...)` | not present (only touch / key / mouse callbacks) |
| `mSwapHook.uninstall()` | `HookHandle` has `reset()` (and its destructor) |
| — | no Mod Menu module was ever registered, so nothing could switch the mod on |

**Fix:** rewritten. Now it registers the mod with `PL_REGISTER_MOD`, registers the
**"V3 Cine Mod"** Mod Menu module (its `onToggle` turns the editor on/off), registers touch +
key callbacks, and hooks `eglSwapBuffers`. Text input is still handled inside `GlHost::onText`
but is no longer registered (the SDK has no text callback; typed characters arrive as key
events and `GlHost::onKey` already converts them).

## 4. `src/GameBridge.cpp` claimed "FULLY IMPLEMENTED" but did not compile
* `cm::game::link()`, `onEnter()`, `onExit()` were **declared in the header and never defined**
  → undefined symbols at link time.
* `out = Minecraft::readPlayerPose(...)` assigned `Minecraft::Pose` to `cm::Pose` — two
  different structs, no conversion → compile error.
* `installCameraHooks()` was never declared in the header, yet called from `MyMod.cpp`.
**Fix:** implemented `link()`, `onEnter()`, `onExit()`, a correct field-by-field pose copy, and
`bool installCameraHooks()` which is an honest no-op returning `false` (it logs a warning).
The RE notes (file VAs, LocalPlayer offsets, the exact place to plug the executor in) are kept
in the file so the remaining work is a small, localised change.

## 5. `src/CameraComponent.h` clashed with `src/LocalPlayerComponent.h`
Both defined `inline ... getCameraComponent(void*)` in `namespace Minecraft` with **different
return types** (`CameraComponent*` vs `void*`) → redeclaration error in any TU that includes both.
**Fix:** the `CameraComponent.h` one is now `cameraComponentAt()`.

## 6. `.github/workflows/build.yml` could never pass the NDK to `build.sh`
```yaml
env:
  ANDROID_NDK_HOME: ${{ env.ANDROID_NDK_LATEST_HOME }}   # always empty
```
`ANDROID_NDK_LATEST_HOME` is a variable on the runner *machine*, not a GitHub Actions `env`
context value, so it expands to nothing and `build.sh` aborts with
`set ANDROID_NDK_HOME to your Android NDK folder`.
**Fix:** the NDK is resolved in the shell step (`$ANDROID_NDK_LATEST_HOME`, else the newest
`$ANDROID_SDK_ROOT/ndk/*`), plus `android-26` is pinned, the artifact includes the
`.levipack`, and `zip` is installed explicitly.

## Not fixed (on purpose — this is missing *game* work, not build work)
`signatures.json` has 3 of 4 signatures truncated (`"FCA54D4D8..."`, `"E30610..."`, `"FFC3..."`),
so they cannot be used for pattern scanning. They are not needed to compile. Until the camera
hooks are implemented, the editor / timeline / segments / transitions / pins all work, but the
**game camera is not driven** and `/camera` commands are only counted, not executed.

## Verified
* every translation unit compiles for `aarch64-linux-android26`
* `libcinematic_mod.so` links, exports `PLGetModRegistration`
* `make -C tests run` → **ALL TESTS PASSED** (122 checks, no ImGui assertion)
