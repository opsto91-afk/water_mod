cmake_minimum_required(VERSION 3.22)
project(water_mod LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Preloader SDK (same version the V3 cine mod uses). Offline copy: -DPRELOADER_SDK_DIR=/path
include(FetchContent)
if(PRELOADER_SDK_DIR)
  set(FETCHCONTENT_SOURCE_DIR_PRELOADER_ANDROID "${PRELOADER_SDK_DIR}" CACHE PATH "" FORCE)
endif()
FetchContent_Declare(
  preloader_android
  GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
  GIT_TAG 0.2.2)
FetchContent_MakeAvailable(preloader_android)

add_library(water_mod SHARED src/MyMod.cpp src/WaterPatch.cpp)
target_include_directories(water_mod PRIVATE src "${preloader_android_SOURCE_DIR}/src")
set_target_properties(water_mod PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  OUTPUT_NAME water_mod)
target_link_options(water_mod PRIVATE -Wl,--exclude-libs,ALL)

find_library(log-lib log REQUIRED)
target_link_libraries(water_mod PRIVATE preloader ${log-lib} dl)
