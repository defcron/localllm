# LocalLlm

Android local-LLM application with Vulkan/GPU support.

## Source layout

The Android project is stored directly in the repository root. The Gradle wrapper, Android app, native build setup, CI helpers, and host regression tests are tracked as ordinary source files; CI does not extract a source ZIP.

## Build and test

The GitHub Actions workflow runs on pushes to `main` and `fix-mobile-vulkan-stability`, pull requests targeting `main`, and manual dispatches.

The workflow installs the pinned Android SDK/NDK/CMake versions, verifies the root-level project layout, checks shell and Python syntax, runs host regression tests, prepares pinned native dependencies, validates the host GLSL compiler, runs Android unit tests, and assembles a debug APK.

To download the APK, open the latest successful **Android build and tests** run under the repository's **Actions** tab and download the `LocalLlm-debug-apk` artifact. Artifacts are temporary CI outputs, not signed production releases.

## Current validation scope

A green CI run confirms the automated checks and debug APK assembly passed on the GitHub-hosted Linux runner. It does not by itself establish runtime compatibility across Android devices or Vulkan drivers; test on target hardware before treating a build as release-ready.

## Contributing

Keep build scripts and workflow paths rooted at the repository root. Do not reintroduce a ZIP-only source layout; source files should remain reviewable and directly editable in GitHub.
