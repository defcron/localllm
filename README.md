# Local LLM — Vulkan, optional OpenCL, and complete MCP tools

Android API 36 / min API 31 / arm64-v8a. This complete source project includes
all requested GPU, tool-visibility, and CI shader-compiler fixes already applied.
No user-side Python patch step is required.

## App settings

- Vulkan is the default backend; OpenCL (Adreno) is selectable.
- GPU layers (-ngl) defaults to -1 (all layers); 0 explicitly selects CPU.
- Context size defaults to 16,384 tokens; the prior 4,096 default is migrated on upgrade.
- GPU backends use batch/microbatch sizes of 128/32 to reduce transient GPU-memory pressure; explicit CPU mode retains 512/512.
- Backend/layer changes persist and apply when Reload model / apply settings is pressed.
- Active reports backend, device, requested layers, and actual offload count when
  reported by the native loader; unknown actual counts stay explicitly unknown.
- GPU load errors do not automatically switch the application to CPU-only mode.
  Individual unsupported graph operations can still run on CPU.
- Every discovered MCP tool, description, and unchanged input schema is included.
  Overlapping names get aliases routed back to each server's original tool name.
- Refresh all tools updates the catalog; automatic list-change subscription is
  not implemented. Large catalogs require sufficient context; tools are not hidden.

## Building through GitHub Actions — no local Python shell needed

The surrounding repository bundle includes a prepared LocalLlm.zip and the active
.github/workflows/build.yml. That workflow installs Python on the runner, extracts
this project, installs Android SDK/NDK/CMake, provisions a modern host glslc, prepares
native dependencies, runs tests/builds, and uploads the debug APK as an artifact.
Use that updated active workflow, not just the root build.yml reference copy.

No patch application commands need to be run on your device. Native dependency
sources and model weights are not bundled; CI fetches the source dependencies.

## Desktop build (optional)

Prerequisites: JDK 17, Android SDK API 36, NDK 28.2.13676358, SDK CMake 3.31.6,
Git/Python/curl/unzip, and a host glslc. The source bootstrap uses Gradle 8.13.

    ./setup.sh
    source ci/provision-glslc.sh
    ./gradlew :app:assembleDebug

The CI path above is provided specifically so you do not need this local shell.

## Compatibility and memory

-1 is an all-layers offload request, not a guarantee that every model fits on the S25 Ultra. Model size,
quantization, context, and runtime buffers determine fit. OpenCL requires the
phone's libOpenCL.so to be visible in the Android linker namespace; the manifest
requests it optionally and no proprietary driver is bundled. Vulkan does not
have an ELF dependency on that optional driver.

F16 KV and auto Flash Attention are backend-compatibility defaults. F16 needs
more cache memory than Q8_0. Compatibility retry disables Flash Attention while
retaining the selected backend. Prefix KV reuse and native Release optimizations
remain; stats distinguish cached/new input and show prefill throughput.

A selected GGUF is imported into private storage once, then reloaded from that
cached path using upstream model loading/mmap. First import needs additional disk
space approximately equal to the file size. Selecting it again reimports; Reload
model reuses the import. Tool calling uses Hermes/Qwen XML JSON; supported model
chat templates and MCP Streamable HTTP are required.

## Validation

12 host tests passed (including native C++ helpers and mock OpenCL/GLSLC paths),
30 source wiring checks passed, workflow YAML/embedded script and shell/Python
syntax checks passed. 16 Kotlin/JUnit tests are included but were not run here.
No actual Android build, real shader compilation, or S25 Ultra runtime test was
performed in the editing environment. See VALIDATION.md for exact limitations.
