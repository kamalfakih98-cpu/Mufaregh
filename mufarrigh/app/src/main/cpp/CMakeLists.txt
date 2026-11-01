cmake_minimum_required(VERSION 3.22)
project(mufarrigh_native C CXX)

# Always optimize: an unoptimized (Debug) ggml is extremely slow.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

if (NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/src/whisper.cpp)
    message(FATAL_ERROR
        "whisper.cpp sources are missing. Run:\n"
        "  git clone --depth 1 --branch v1.7.6 https://github.com/ggml-org/whisper.cpp app/src/main/cpp/whisper.cpp")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# ---- ggml: CPU only, linked statically into our single .so ----
# Target ARMv8.2 + fp16 + dot-product (Cortex-A78 class and newer, e.g. Dimensity 7300).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

add_library(
    mufarrigh_whisper
    SHARED
    whisper_jni.cpp
    ${WHISPER_DIR}/src/whisper.cpp
)

target_compile_definitions(mufarrigh_whisper PRIVATE GGML_USE_CPU)

target_include_directories(
    mufarrigh_whisper
    PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
)

target_compile_options(mufarrigh_whisper PRIVATE -O3 -ffunction-sections -fdata-sections)
target_link_options(mufarrigh_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

find_library(LOG_LIB log)
target_link_libraries(mufarrigh_whisper PRIVATE ggml ${LOG_LIB} android)
