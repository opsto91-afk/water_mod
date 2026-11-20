cmake_minimum_required(VERSION 3.22.1)
project(zaphre_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(zaphre_bridge SHARED
    elf_analyzer.cpp
    zaphre_bridge.cpp
)

# Android 15/16 16 KB page-size compatibility (matches LeviLauncher v1.7.1)
target_link_options(zaphre_bridge PRIVATE
    "-Wl,-z,max-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(zaphre_bridge
    ${log-lib}
    dl
)

# Pinned upstream llama.cpp, built into the APK as a CPU-only arm64 JNI library.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(llama_cpp URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/6f5c9ad21746f69bbb7f3cb295a90e53fe316963)
FetchContent_MakeAvailable(llama_cpp)
add_library(zaphre_llama SHARED local_llama.cpp)
target_include_directories(zaphre_llama PRIVATE ${llama_cpp_SOURCE_DIR}/common ${llama_cpp_SOURCE_DIR}/include ${llama_cpp_SOURCE_DIR}/ggml/include ${llama_cpp_SOURCE_DIR}/ggml/src)
target_compile_definitions(zaphre_llama PRIVATE LOG_MIN_LEVEL=ANDROID_LOG_WARN)
target_link_libraries(zaphre_llama llama llama-common android log)
target_link_options(zaphre_llama PRIVATE "-Wl,-z,max-page-size=16384")
