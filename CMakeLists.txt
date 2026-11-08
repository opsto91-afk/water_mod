cmake_minimum_required(VERSION 3.22)
project(water_mod LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Preloader SDK.
# MUST match the preloader bundled inside LeviLaunchroid (app/src/main/cpp/preloader
# submodule of LiteLDev/LeviLaunchroid @ cc08b40a31471d3e2dedb267f5f17b28da2b0604).
# ABI warning: the bundled preloader's pl::modmenu::ModuleInfo carries a trailing `onKeybind`
# std::function that 0.2.2 does not have. Building against 0.2.2 makes the in-game
# pl::modmenu::registerModule() read ~32 bytes past the mod's ModuleInfo temporary and copy
# a garbage std::function -> the manager function pointer is dereferenced -> the game process
# dies silently a few seconds after enable(), while the screen/UI is coming up.
# (Tag 0.2.3 predates the onKeybind/ConfigType additions as well, so pin the exact commit.)
# Offline copy: -DPRELOADER_SDK_DIR=/path
include(FetchContent)
if(PRELOADER_SDK_DIR)
  set(FETCHCONTENT_SOURCE_DIR_PRELOADER_ANDROID "${PRELOADER_SDK_DIR}" CACHE PATH "" FORCE)
endif()
FetchContent_Declare(
  preloader_android
  GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
  GIT_TAG cc08b40a31471d3e2dedb267f5f17b28da2b0604)
FetchContent_MakeAvailable(preloader_android)

add_library(water_mod SHARED src/MyMod.cpp src/WaterPatch.cpp)
target_include_directories(water_mod PRIVATE src)
set_target_properties(water_mod PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  OUTPUT_NAME water_mod)
target_link_options(water_mod PRIVATE -Wl,--exclude-libs,ALL)

find_library(log-lib log REQUIRED)
target_link_libraries(water_mod PRIVATE preloader ${log-lib} dl)
