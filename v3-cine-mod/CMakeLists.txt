cmake_minimum_required(VERSION 3.22)
project(cinematic_mod LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------- Preloader SDK
# Default: fetched from GitHub (needs internet at configure time).
# Offline / local copy:  cmake ... -DPRELOADER_SDK_DIR=/path/to/preloader-android
include(FetchContent)
if(PRELOADER_SDK_DIR)
  set(FETCHCONTENT_SOURCE_DIR_PRELOADER_ANDROID "${PRELOADER_SDK_DIR}" CACHE PATH "" FORCE)
endif()
FetchContent_Declare(
  preloader_android
  GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
  GIT_TAG 0.2.2)
FetchContent_MakeAvailable(preloader_android)

# ---------------------------------------------------------------------- ImGui
set(IMGUI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/imgui)
set(IMGUI_SOURCES
  ${IMGUI_DIR}/imgui.cpp
  ${IMGUI_DIR}/imgui_draw.cpp
  ${IMGUI_DIR}/imgui_tables.cpp
  ${IMGUI_DIR}/imgui_widgets.cpp
  ${IMGUI_DIR}/imgui_impl_opengl3.cpp)
# imgui_impl_android.cpp / imgui_demo.cpp are intentionally NOT built:
#  - touch input is routed by src/TouchRouter.cpp (multi-touch)
#  - the demo window is not needed

# ------------------------------------------------------------------------ mod
add_library(cinematic_mod SHARED
  src/MyMod.cpp
  src/GlHost.cpp
  src/GameBridge.cpp
  src/CameraLink.cpp
  src/Editor.cpp
  src/TouchRouter.cpp
  src/Core.cpp
  ${IMGUI_SOURCES})

target_include_directories(cinematic_mod PRIVATE src ${IMGUI_DIR})
target_compile_definitions(cinematic_mod PRIVATE IMGUI_IMPL_OPENGL_ES3)

# Keep every ImGui symbol private to this .so (the launcher / other mods may ship their own ImGui).
set_target_properties(cinematic_mod PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  OUTPUT_NAME cinematic_mod)
target_link_options(cinematic_mod PRIVATE -Wl,--exclude-libs,ALL)

find_library(log-lib log REQUIRED)
find_library(android-lib android REQUIRED)
find_library(egl-lib EGL REQUIRED)
find_library(gles-lib GLESv3 REQUIRED)

target_link_libraries(cinematic_mod PRIVATE preloader ${log-lib} ${android-lib} ${egl-lib} ${gles-lib} dl)
