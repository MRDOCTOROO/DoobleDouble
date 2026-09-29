cmake_minimum_required(VERSION 3.22.1)

project(dooble_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(dooble_native SHARED
        native_bridge.cpp
        plt_hook.cpp
        io_redirect.cpp
)

target_compile_options(dooble_native PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=hidden
        -O2
)

find_library(log_lib log)
find_library(dl_lib dl)

target_link_libraries(dooble_native
        ${log_lib}
        ${dl_lib}
)
