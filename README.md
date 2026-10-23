# Local LLM — Vulkan, optional OpenCL, and MCP tools

Android API 36 / min API 31 / arm64-v8a. This repository contains the complete, directly editable Android source project.

## Source layout

The project source is tracked in the repository itself: `app/`, Gradle build files and wrapper, native setup scripts, CI helpers, tests, and documentation all live at the root. There is no source ZIP extraction step. Edit and review the actual files in GitHub, and use the root-level Gradle wrapper for builds.

## App settings and behavior

- Vulkan is the default backend; OpenCL (Adreno) is selectable.
- GPU layers (`-ngl`) defaults to `-1` (request all layers); `0` explicitly selects CPU.
- Context size defaults to 16,384 tokens; the prior 4,096 default is migrated on upgrade.
- Vulkan uses batch/microbatch 128/32 for conservative mobile GPU memory use. OpenCL uses 512/128 (larger prompt batches split into bounded microbatches); explicit CPU mode retains 512/512.
- Backend/layer changes persist and apply when Reload model / apply settings is pressed.
- Active reports backend, device, requested layers, and actual offload count when reported by the native loader; unknown actual counts stay explicitly unknown.
- GPU load errors do not automatically switch the application to CPU-only mode. Individual unsupported graph operations can still run on CPU.
- All discovered MCP tools remain available in a compact name/description catalog; only locally selected or explicitly discovered tools include full input schemas in the model prompt. A built-in schema-discovery call loads relevant schemas on demand, and a missed selection can safely expose a schema and retry without executing malformed arguments. Tool calls still route through MCP with original server tool names. Overlapping names get aliases routed back to each server's original tool name.
- The local lexical selector is a best-effort optimization, not an access-control filter: the full catalog and explicit discovery fallback remain available. Refresh all tools updates the catalog; automatic list-change subscription is not implemented.

## Build and test with GitHub Actions

The **Android build and tests** workflow builds directly from the repository root. It installs the pinned Android SDK/NDK/CMake versions, verifies the root project layout, checks shell and Python syntax, runs host regression tests, prepares pinned native dependencies, validates the host GLSL compiler, runs Android unit tests, assembles a debug APK, and uploads it as the `LocalLlm-debug-apk` artifact.

To download the APK, open the latest successful run under the repository's **Actions** tab and download that artifact. It is a debug build, not a signed production release.

## Desktop build (optional)

Prerequisites: JDK 17, Android SDK API 36, NDK 28.2.13676358, SDK CMake 3.31.6, Git/Python/curl/unzip, and a host `glslc`.

```sh
./setup.sh
source ci/provision-glslc.sh
./gradlew :app:assembleDebug
```

Native dependency sources and model weights are not bundled; the setup scripts fetch pinned native dependencies.

## Compatibility and memory

`-1` is an all-layers offload request, not a guarantee that every model fits on the S25 Ultra. Model size, quantization, context, and runtime buffers determine fit. OpenCL requires the phone's `libOpenCL.so` to be visible in the Android linker namespace; the manifest requests it optionally and no proprietary driver is bundled. Vulkan does not have an ELF dependency on that optional driver.

F16 KV and auto Flash Attention are backend-compatibility defaults. F16 needs more cache memory than Q8_0. Compatibility retry disables Flash Attention while retaining the selected backend. Prefix KV reuse and native Release optimizations remain; stats distinguish cached/new input and show prefill throughput.

A selected GGUF is imported into private storage once, then reloaded from that cached path using upstream model loading/mmap. First import needs additional disk space approximately equal to the file size. Selecting it again reimports; Reload model reuses the import. Tool calling uses Hermes/Qwen XML JSON; supported model chat templates and MCP Streamable HTTP are required.

## Validation scope

A green CI run confirms automated checks and debug APK assembly passed on the GitHub-hosted Linux runner. It does not establish runtime compatibility across Android devices or Vulkan drivers; test on target hardware before treating a build as release-ready. See [VALIDATION.md](VALIDATION.md) for additional validation notes.
