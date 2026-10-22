# Package config for SPIRV-Headers, supplied for find_package(SPIRV-Headers CONFIG REQUIRED)
# in llama.cpp's ggml-vulkan CMake. Upstream only ships this config from `cmake --install`,
# and CI consumes the vendored git checkout instead, so this file points the package at it.
#
# The including CMakeLists must set SPIRV_HEADERS_ROOT to the SPIRV-Headers checkout
# (app/src/main/cpp/vendor/SPIRV-Headers, created by setup.sh).

if(NOT DEFINED SPIRV_HEADERS_ROOT OR SPIRV_HEADERS_ROOT STREQUAL "")
    message(FATAL_ERROR "SPIRV-Headers: SPIRV_HEADERS_ROOT is not set")
endif()
if(NOT EXISTS "${SPIRV_HEADERS_ROOT}/include/spirv/unified1/spirv.h")
    message(FATAL_ERROR "SPIRV-Headers: spirv.h not found under ${SPIRV_HEADERS_ROOT}. Run ./setup.sh in LocalLlm.")
endif()

if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED GLOBAL)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_HEADERS_ROOT}/include")
endif()

set(SPIRV-Headers_FOUND TRUE)
