//-----------------------------------------------------------------------------
// imconfig.h  (added: the original zip did not contain this file)
//-----------------------------------------------------------------------------
#pragma once

// We never use the built-in demo / metrics windows in this project.
#define IMGUI_DISABLE_DEMO_WINDOWS
#define IMGUI_DISABLE_DEBUG_TOOLS
// ImGui symbols are kept private by building with -fvisibility=hidden (see CMakeLists.txt),
// so they cannot clash with another ImGui copy inside the launcher or other mods.
