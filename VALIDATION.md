# Validation status

Source archive: GitHub repository `defcron/localllm`, originally retrieved from commit `82ef062138abdd7f6f0f0981ac119da1455e1040`. The updated project archive contains the following changes:

- Default GPU layers changed from 99 to -1 (all layers).
- Default context changed from 4,096 to 16,384 tokens.
- Existing stored values matching the old defaults are migrated to the new defaults.
- GPU prefill batching is reduced to batch 128 / microbatch 32; explicit CPU mode retains 512 / 512.
- Regression tests and user-facing settings documentation were updated.

Verified during this edit:

- Read all 56 entries in the original archive and checked each entry's uncompressed size and CRC32.
- Rebuilt the ZIP central directory and local headers; checked every entry's CRC32 and size after rebuilding.
- Re-fetched the committed ZIP from GitHub and confirmed that its blob SHA matches the uploaded archive.

Not executed in this editing environment:

- Python host regression tests.
- Kotlin/JUnit tests.
- Gradle, CMake, Android NDK native build, or debug APK assembly.
- Real GLSL shader compilation or installation/runtime testing on the Samsung Galaxy S25 Ultra.
- Live Vulkan/OpenCL stability, GPU memory use, generation speed, or UI responsiveness on-device.

A repository-level GitHub Actions workflow is provided at `.github/workflows/build.yml` on the accompanying branch to run host checks, prepare native dependencies, run Kotlin tests, build the Android APK, and upload it as an artifact. The workflow has not been confirmed passing in this session. The project ZIP itself does not contain the surrounding repository workflow.

ZIP validation proves archive integrity, not that the Android project compiles or that a device-specific Vulkan crash is fixed. Those require the build and on-device checks listed above.
