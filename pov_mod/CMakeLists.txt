cmake_minimum_required(VERSION 3.22)
project(screen_pov_mod LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------- Preloader SDK
# Pinned to the exact commit of the preloader bundled inside LeviLaunchroid
# (LiteLDev/LeviLaunchroid @ cc08b40a31471d3e2dedb267f5f17b28da2b0604). Same pin as water_mod / cine_v4.
# Offline / local copy:  cmake ... -DPRELOADER_SDK_DIR=/path/to/preloader-android
include(FetchContent)
if(PRELOADER_SDK_DIR)
  set(FETCHCONTENT_SOURCE_DIR_PRELOADER_ANDROID "${PRELOADER_SDK_DIR}" CACHE PATH "" FORCE)
endif()
FetchContent_Declare(
  preloader_android
  GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
  GIT_TAG cc08b40a31471d3e2dedb267f5f17b28da2b0604)
FetchContent_MakeAvailable(preloader_android)

# ------------------------------------------------------------------------ mod
add_library(pov_mod SHARED
  src/MyMod.cpp
  src/PovBridge.cpp)

target_include_directories(pov_mod PRIVATE src)

# Keep every symbol private to this .so (the launcher / other mods may ship their own).
set_target_properties(pov_mod PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  OUTPUT_NAME pov_mod)
target_link_options(pov_mod PRIVATE -Wl,--exclude-libs,ALL)

find_library(log-lib log REQUIRED)
find_library(android-lib android REQUIRED)

target_link_libraries(pov_mod PRIVATE preloader ${log-lib} ${android-lib} dl)
