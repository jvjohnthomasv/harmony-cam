cmake_minimum_required(VERSION 3.22.1)
project(harmonycam LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(harmonycam SHARED
        jni_bridge.cpp
        engine/AudioEngine.cpp)

target_include_directories(harmonycam PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# The pitch-shifter is number-crunching heavy: always optimise, even in debug builds.
target_compile_options(harmonycam PRIVATE -O3 -ffast-math -fno-finite-math-only -Wall -Wno-unused-result -Wno-unused-function)

target_link_libraries(harmonycam oboe::oboe log android)
