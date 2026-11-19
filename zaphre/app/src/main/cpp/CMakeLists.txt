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
