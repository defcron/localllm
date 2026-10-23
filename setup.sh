#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v git >/dev/null;command -v python3 >/dev/null
cpp=app/src/main/cpp;vendor="$cpp/vendor";mkdir -p "$vendor"
clone() {
    local url="$1" directory="$2" ref="$3"
    if [[ ! -d "$directory/.git" ]];then git clone --depth 1 --branch "$ref" "$url" "$directory";fi
}
clone https://github.com/ggml-org/llama.cpp "$cpp/llama.cpp" v0.6.0
git -C "$cpp/llama.cpp" show-ref --verify --quiet refs/tags/v0.6.0 || git -C "$cpp/llama.cpp" fetch --depth 1 origin tag v0.6.0
[[ "$(git -C "$cpp/llama.cpp" rev-parse HEAD)" == "$(git -C "$cpp/llama.cpp" rev-parse 'v0.6.0^{commit}')" ]] || { echo 'Expected llama.cpp v0.6.0; preserve any local changes before checking out that tag.' >&2;exit 1; }
clone https://github.com/KhronosGroup/OpenCL-Headers "$vendor/OpenCL-Headers" main
clone https://github.com/KhronosGroup/OpenCL-CLHPP "$vendor/OpenCL-CLHPP" main
# Vulkan-Hpp and Vulkan-Headers must be from the same release: the Hpp header
# contains a VK_HEADER_VERSION static assertion that must match vulkan_core.h.
clone https://github.com/KhronosGroup/Vulkan-Headers "$vendor/Vulkan-Headers" v1.4.365
clone https://github.com/KhronosGroup/Vulkan-Hpp "$vendor/Vulkan-Hpp" v1.4.365
clone https://github.com/KhronosGroup/SPIRV-Headers "$vendor/SPIRV-Headers" main
python3 tools/prepare_headers.py
python3 tools/generate_opencl_dispatch.py "$vendor/opencl-include/CL/cl.h" "$vendor/opencl_dispatch.cpp"
python3 tools/verify_native_api.py "$cpp/llama.cpp"
python3 tools/lock_dependencies.py
