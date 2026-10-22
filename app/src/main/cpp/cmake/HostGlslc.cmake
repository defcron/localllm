include_guard(GLOBAL)
# Select a compiler for the build host, not the Android target sysroot.
set(_localllm_glslc "")
if(DEFINED LOCALLLM_GLSLC AND NOT "${LOCALLLM_GLSLC}" STREQUAL "")
    set(_localllm_glslc "${LOCALLLM_GLSLC}")
elseif(DEFINED ENV{GLSLC} AND NOT "$ENV{GLSLC}" STREQUAL "")
    set(_localllm_glslc "$ENV{GLSLC}")
elseif(DEFINED Vulkan_GLSLC_EXECUTABLE AND NOT "${Vulkan_GLSLC_EXECUTABLE}" STREQUAL "" AND
       NOT "${Vulkan_GLSLC_EXECUTABLE}" MATCHES "-NOTFOUND$")
    set(_localllm_glslc "${Vulkan_GLSLC_EXECUTABLE}")
else()
    unset(_localllm_glslc CACHE)
    unset(_localllm_glslc)
    find_program(_localllm_glslc NAMES glslc glslc.exe
        HINTS "${VULKAN_SDK}/bin" "${VULKAN_SDK}/Bin"
              "$ENV{VULKAN_SDK}/bin" "$ENV{VULKAN_SDK}/Bin"
        NO_CMAKE_FIND_ROOT_PATH NO_CACHE)
endif()
if(NOT _localllm_glslc OR NOT EXISTS "${_localllm_glslc}" OR IS_DIRECTORY "${_localllm_glslc}")
    message(FATAL_ERROR "Host glslc is missing. CI must run LocalLlm/ci/provision-glslc.sh before Gradle. Set GLSLC to an absolute HOST executable path locally; do not disable Vulkan.")
endif()
file(REAL_PATH "${_localllm_glslc}" _localllm_glslc)
execute_process(COMMAND "${_localllm_glslc}" --version
    RESULT_VARIABLE _localllm_result OUTPUT_VARIABLE _localllm_version ERROR_VARIABLE _localllm_error TIMEOUT 15)
if(NOT "${_localllm_result}" STREQUAL "0")
    message(FATAL_ERROR "glslc cannot run on this build host: ${_localllm_glslc}. ${_localllm_error}")
endif()
unset(GLSLC_HOST CACHE)
set(GLSLC_HOST "${_localllm_glslc}" CACHE FILEPATH "Host Vulkan shader compiler" FORCE)
set(Vulkan_GLSLC_EXECUTABLE "${_localllm_glslc}" CACHE FILEPATH "Host Vulkan shader compiler" FORCE)
list(APPEND CMAKE_TRY_COMPILE_PLATFORM_VARIABLES Vulkan_GLSLC_EXECUTABLE LOCALLLM_GLSLC VULKAN_SDK)
list(REMOVE_DUPLICATES CMAKE_TRY_COMPILE_PLATFORM_VARIABLES)
message(STATUS "LocalLlm host shader compiler: ${Vulkan_GLSLC_EXECUTABLE}")
message(STATUS "${_localllm_version}")
