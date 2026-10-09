#!/usr/bin/env bash
set -euo pipefail
fail() { printf 'glslc provisioning: %s\n' "$*" >&2; exit 1; }
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
sdk="";compiler=""
if [[ -n "${GLSLC:-}" ]]; then
    compiler="$GLSLC"
elif [[ -n "${VULKAN_SDK:-}" ]]; then
    for candidate in "$VULKAN_SDK/bin/glslc" "$VULKAN_SDK/Bin/glslc.exe"; do
        if [[ -x "$candidate" ]]; then compiler="$candidate";sdk="$VULKAN_SDK";break;fi
    done
fi
if [[ -z "$compiler" ]]; then
    [[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || fail 'Set GLSLC to a host compiler; automatic provisioning supports Linux x86_64.'
    for command in curl tar xz python3;do command -v "$command" >/dev/null || fail "Install $command";done
    version="${VULKAN_SDK_VERSION:-}"
    if [[ -z "$version" ]];then
        version=$(curl --fail --silent --show-error --location --retry 3 https://vulkan.lunarg.com/sdk/latest/linux.txt | tr -d '\r\n ')
    fi
    [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Unexpected SDK version: $version"
    install_root="${LOCALLLM_VULKAN_SDK_HOME:-${RUNNER_TEMP:-$HOME/.cache}/localllm-vulkan-sdk}"
    sdk="$install_root/$version/x86_64";compiler="$sdk/bin/glslc"
    if [[ ! -x "$compiler" ]];then
        mkdir -p "$install_root"
        curl --fail --show-error --location --retry 3 "https://sdk.lunarg.com/sdk/download/$version/linux/vulkan_sdk.tar.xz" -o "$temporary/sdk.tar.xz"
        tar --extract --xz --file "$temporary/sdk.tar.xz" --directory "$install_root" --no-same-owner --no-same-permissions
    fi
fi
[[ -x "$compiler" ]] || fail "Not an executable host compiler: $compiler"
compiler=$(python3 - "$compiler" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).resolve())
PY
)
[[ "$compiler" != *$'\n'* && "$compiler" != *$'\r'* ]] || fail 'Invalid compiler path'
if [[ -n "$sdk" && -d "$sdk/lib" ]];then export LD_LIBRARY_PATH="$sdk/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}";fi
"$compiler" --version || fail 'Compiler cannot execute on this host'
cat > "$temporary/probe.comp" <<'GLSL'
#version 450
layout(local_size_x=1,local_size_y=1,local_size_z=1) in;
void main() {}
GLSL
"$compiler" --target-env=vulkan1.2 -fshader-stage=compute "$temporary/probe.comp" -o "$temporary/probe.spv" || fail 'Compute shader preflight failed'
python3 - "$temporary/probe.spv" <<'PY'
from pathlib import Path
import struct,sys
data=Path(sys.argv[1]).read_bytes()
if len(data)<20 or len(data)%4 or struct.unpack('<I',data[:4])[0]!=0x07230203:
    raise SystemExit('Invalid SPIR-V header from glslc preflight')
PY
export GLSLC="$compiler" Vulkan_GLSLC_EXECUTABLE="$compiler"
export PATH="$(dirname "$compiler"):$PATH"
if [[ -n "${GITHUB_ENV:-}" ]];then
    printf 'GLSLC=%s\nVulkan_GLSLC_EXECUTABLE=%s\n' "$compiler" "$compiler" >> "$GITHUB_ENV"
    if [[ -n "$sdk" ]];then
        [[ "$sdk" != *$'\n'* && "$sdk" != *$'\r'* ]] || fail 'Invalid SDK path'
        printf 'VULKAN_SDK=%s\nLD_LIBRARY_PATH=%s\n' "$sdk" "${LD_LIBRARY_PATH:-}" >> "$GITHUB_ENV"
    fi
fi
if [[ -n "${GITHUB_PATH:-}" ]];then printf '%s\n' "$(dirname "$compiler")" >> "$GITHUB_PATH";fi
printf 'Validated host GLSLC: %s\n' "$compiler"
