# References (jin se seekha / jo mile)

## 1. Pojav Controls Fixed (LeviLauncher plugin, "Craftandoof", v1.4.1)
- Chhota C shim + andar rakhi Java (DEX). `InMemoryDexClassLoader` se load hota hai, launcher ka class loader parent hota hai.
- Launcher ke andar pehle se **input injection** hai: `PojavControlsMod.nativeSendKey / nativeSendMouseButton / nativeSendScroll / nativeSendLookDelta / nativeSendPointer`,
  aur `GyroMod.nativeUpdateDelta` (look). `PreloaderInput.isHudScreenOpen / isShowingMenu / isPauseMenuOpen` game ki state batate hain.
- Fallback: game ke SurfaceView par Android `MotionEvent` / `KeyEvent` dispatch.
- Ye game ko **keyboard + mouse mode** me chalata hai; MCPE ke apne "Keyboard & Mouse" settings (screenshots) wahi use hote hain.
- `.levipack` = flat zip (`manifest.json` root me). Mod id = folder ka naam.
- Safety: embedded DEX me network / exec / socket ke strings nahi mile; native part sirf malloc/memcpy/dladdr/log import karta hai. **Poora audit nahi**; prebuilt ki jagah source se build karo.
- Isse hum kya lete hain: **player ko move karne ke liye hamari joystick ki jagah ye**. Hamara mod ise nahi badalta, sirf touch ko rasta deta hai.

## 2. Do a Barrel Roll (open source, **GPL-3.0**, Minecraft **26.52** Android)
- Original: theaddonn/Do-A-Barrel-Roll-Bedrock. Android/LeviLauncher port: MrPokeG.
- Isme mila: 18 arm64 byte signatures (client update, player tick, applyTurn/localTurn, setupCamera, cameraComponent, renderPrep, render, tryGlide, ...),
  offsets (ActorRotation 0x218, ActorState 0x208, Velocity 0x18, CameraOrientation 0x28, client vtable slot 32 = local player),
  ECS component lookup (type name ka FNV-1a hash), runtime signature scanner + hook installer.
- **Sirf 26.52.** Tumhara game 60.4 hai → `CODEX_PROMPT_1_camera_60.4.txt`.
- License: code copy karoge to apna mod GPL-3 + credit. Offsets/patterns "tathya" hain, par credit zaroor do.

## 3. Ghidra analysis (4 summary files, AI agent ne banayi)
- Raw Ghidra export nahi, summaries. `eglSwapBuffers` imported (bgfx renderer thread + Swappy), context GLES2/3.
- Risk jo mila: Vulkan chale to `eglSwapBuffers` hook nahi chalega (bgfx Vulkan ko dlopen se load karta hai, to "imports me vk nahi" ka matlab nahi). Tumhare phone par GLES chal raha hai.

## 4. HeroCine mcaddon se liya gaya
- N keys = N-1 segments, har segment: duration, ease (32 types), transition (Cut / Hold / Fade, max 2s).
- Commands jo add-on `runCommand` se chalata hai (hum bhi wahi banate hain): `hud @s hide all`, `camera @s fov_set ...`, `camera @s fov_clear`, `camera @s clear`, `camerashake`.
- Camera tick-by-tick `player.camera.setCamera("minecraft:free", {location, rotation})` se lagta hai (Script API) — native me iska equivalent hook chahiye.

## 5. LeviLauncher Preloader SDK (`pl::` API)
`pl::memory::hook`, `resolveSignature`, `pl::input::registerTouchCallback / Key / Text`, `pl::modmenu::ModuleBuilder`. Android pe `ll::` (LeviLamina) nahi chalta.
